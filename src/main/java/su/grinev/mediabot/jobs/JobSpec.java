package su.grinev.mediabot.jobs;

import su.grinev.mediabot.graph.Fingerprint;
import su.grinev.mediabot.graph.Graph;
import su.grinev.mediabot.graph.Node;
import su.grinev.mediabot.graph.Pipeline;
import su.grinev.mediabot.media.AudioFormat;
import su.grinev.mediabot.media.Container;
import su.grinev.mediabot.media.Quality;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One job, described in full, before anything is written down.
 *
 * <p>This is the only thing the model gets to decide. It picks a scenario and fills this in; from
 * the moment it reaches {@link JobQueue} the job's progress through its states belongs to the code
 * and nothing else can move it. Which is why the record is exhaustive: a worker that has to guess a
 * missing height, or default an audio format halfway down, is a second place where the shape of the
 * work is decided — and the two places disagree eventually.
 *
 * <p>Normalised on construction rather than checked later. A height on an audio-only job is not an
 * error worth refusing, it is a field that means nothing, so it is cleared here instead of being
 * carried to somewhere that has to remember to ignore it.
 *
 * @param chatId who asked, and the only chat the result may be shown to
 * @param scenario which of the three shapes of work this is
 * @param url already a link; whether it is one this bot may fetch is {@code UrlGuard}'s question
 * @param maxHeight ceiling for a video, the target for a transcode, null for "best available"
 * @param audioFormat container for an audio job, null for the others
 * @param title what the host calls it, once probing has said; null until then
 */
public record JobSpec(long chatId, JobKind scenario, String url, Integer maxHeight,
                      String audioFormat, String title, Graph graph) {

    private static final String DEFAULT_AUDIO_FORMAT = "m4a";

    /** A transcode with no height asked for still has to encode to something. */
    private static final int DEFAULT_TRANSCODE_HEIGHT = 720;

    public JobSpec {
        if (scenario == null) {
            throw new IllegalArgumentException("a job needs a scenario");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("a job needs a url");
        }
        url = url.strip();
        switch (scenario) {
            case AUDIO -> {
                maxHeight = null;
                audioFormat = audioFormat == null || audioFormat.isBlank() ? DEFAULT_AUDIO_FORMAT : audioFormat.toLowerCase(Locale.ROOT);
            }
            case TRANSCODE -> {
                maxHeight = maxHeight == null ? DEFAULT_TRANSCODE_HEIGHT : maxHeight;
                audioFormat = null;
            }
            case DOWNLOAD -> audioFormat = null;
        }
        if (graph == null) {
            graph = switch (scenario) {
                case DOWNLOAD -> Pipeline.download(url, maxHeight).build();
                case AUDIO -> Pipeline.download(url, null)
                        .audio(AudioFormat.orDefault(audioFormat)).build();
                case TRANSCODE -> Pipeline.download(url, null)
                        .encode(Container.DEFAULT, maxHeight, Quality.DEFAULT).build();
            };
        }
    }

    public JobSpec(long chatId, JobKind scenario, String url, Integer maxHeight,
                   String audioFormat, String title) {
        this(chatId, scenario, url, maxHeight, audioFormat, title, null);
    }

    /**
     * A job from a graph the caller assembled, with the flat fields taken back out of it.
     *
     * <p>The graph is what runs; {@code kind}, {@code url} and {@code maxHeight} are a copy kept
     * beside it so the queue can dedupe and the chat can say what a job is without opening it. They
     * are derived here and nowhere else, which is what keeps the copy from disagreeing.
     */
    public static JobSpec of(long chatId, Graph graph) {
        Node.Fetch source = graph.source().orElseThrow(
                () -> new IllegalArgumentException("a job has to start with something to download"));

        Optional<Node.Audio> audio = firstOf(graph, Node.Audio.class);
        Optional<Node.Encode> encode = firstOf(graph, Node.Encode.class);
        JobKind kind = audio.isPresent() ? JobKind.AUDIO
                : encode.isPresent() ? JobKind.TRANSCODE
                : JobKind.DOWNLOAD;
        Integer height = encode.map(Node.Encode::height).orElse(source.maxHeight());

        return new JobSpec(chatId, kind, source.url(), height,
                audio.map(node -> node.format().extension()).orElse(null), null, graph);
    }

    private static <T extends Node> Optional<T> firstOf(Graph graph, Class<T> type) {
        return graph.nodes().stream().filter(type::isInstance).map(type::cast).findFirst();
    }

    public static JobSpec download(long chatId, String url, Integer maxHeight) {
        return new JobSpec(chatId, JobKind.DOWNLOAD, url, maxHeight, null, null);
    }

    public static JobSpec transcode(long chatId, String url, Integer height) {
        return new JobSpec(chatId, JobKind.TRANSCODE, url, height, null, null);
    }

    public static JobSpec audio(long chatId, String url, String format) {
        return new JobSpec(chatId, JobKind.AUDIO, url, null, format, null);
    }

    /**
     * The url after {@code UrlGuard} has had it, which is the one that must be fetched — so it is
     * replaced in the graph too rather than only in the copy beside it.
     */
    public JobSpec withUrl(String checked) {
        List<Node> rewritten = graph.nodes().stream()
                .map(node -> node instanceof Node.Fetch fetch
                        ? (Node) new Node.Fetch(fetch.id(), checked, fetch.maxHeight())
                        : node)
                .toList();
        return new JobSpec(chatId, scenario, checked, maxHeight, audioFormat, title,
                new Graph(rewritten));
    }

    /**
     * The same job, with every height in it brought under {@code ceiling}.
     *
     * <p>Applied to the graph rather than to the request, which is why a chain cannot walk around
     * it: {@code /download <link> /encode 4k} and a bare link are the same two nodes by the time
     * this runs, and both get clamped. A height nobody asked for — a plain download, or MAX, both
     * of which mean "whatever the source is" — becomes the ceiling, because "whatever" is exactly
     * what a ceiling exists to bound.
     */
    public JobSpec cappedAt(int ceiling) {
        List<Node> capped = graph.nodes().stream().map(node -> switch (node) {
            case Node.Fetch fetch ->
                    (Node) new Node.Fetch(fetch.id(), fetch.url(), atMost(fetch.maxHeight(), ceiling));
            case Node.Encode encode ->
                    (Node) new Node.Encode(encode.id(), encode.input(), encode.container(),
                            atMost(encode.height(), ceiling), encode.quality());
            default -> node;
        }).toList();
        return JobSpec.of(chatId, new Graph(capped)).withTitle(title);
    }

    private static Integer atMost(Integer height, int ceiling) {
        return height == null ? ceiling : Math.min(height, ceiling);
    }

    public JobSpec withTitle(String probed) {
        return new JobSpec(chatId, scenario, url, maxHeight, audioFormat, probed, graph);
    }

    /**
     * The fingerprint this job is deduped on: what work was asked for, and nothing about who asked
     * or what the host later called it.
     *
     * <p>Taken from the whole graph rather than from the url, because the url is not the work. The
     * same link at two heights is two files; the same link with a cut is a third; the same link
     * with the cut normalised is a fourth. Every step and every parameter counts, and the graph is
     * the only place that has all of them.
     *
     * <p>Hashed from {@link Fingerprint}, which is written out by hand, and not from the stored
     * json. A fingerprint taken from the storage format changes whenever that format does — reorder
     * one field and every row already deduped stops matching — and it never agreed with a second
     * implementation of the same format.
     *
     * <p>A graph that cannot be written down has no fingerprint, and a job with no fingerprint is
     * simply never deduped — it does the work again rather than handing back the wrong file.
     */
    public String origin() {
        try {
            byte[] sum = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(Fingerprint.of(graph).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(sum.length * 2);
            for (byte b : sum) {
                hex.append("%02x".formatted(b));
            }
            return hex.toString();
        } catch (RuntimeException | java.security.NoSuchAlgorithmException e) {
            return null;
        }
    }

    /** What was asked for, in the words a person would use. */
    public String describe() {
        boolean plain = graph.outputs().size() == 1 && graph.nodes().stream().noneMatch(
                node -> node instanceof Node.Cut || node instanceof Node.Normalize
                        || node instanceof Node.Concat);
        if (!plain) {
            return graph.describe();
        }
        return switch (scenario) {
            case DOWNLOAD -> maxHeight == null ? "best quality" : "up to " + maxHeight + "p";
            case TRANSCODE -> "re-encoded to " + maxHeight + "p";
            case AUDIO -> "audio only, as " + audioFormat;
        };
    }
}
