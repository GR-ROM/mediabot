package su.grinev.mediabot.graph;

import su.grinev.mediabot.media.AudioFormat;
import su.grinev.mediabot.media.Container;
import su.grinev.mediabot.media.Quality;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import su.grinev.mediabot.media.DownloadProgress;
import su.grinev.mediabot.media.Ffmpeg;
import su.grinev.mediabot.media.MediaInfo;
import su.grinev.mediabot.media.VideoDownloader;
import su.grinev.mediabot.media.YtDlp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Walks a {@link Graph} and produces one file per {@code Publish}.
 *
 * <p>Nodes are run in topological order with each one's output kept under its id, so a step that
 * two branches share is done once and a branch that forks costs only what it adds. Nothing here
 * decides what the work is — that was settled when the graph was built — which is what makes the
 * whole of the execution a walk rather than a series of judgements.
 *
 * <p>Two decisions are taken before the walk, both of which only ever save work. A fetch whose only
 * consumer is {@code Audio} asks yt-dlp for the audio alone, so no video bytes are moved to be
 * thrown away, and that is what keeps a plain audio request as cheap as it was before there was a
 * graph. A fetch that feeds an encode picks its streams for re-encoding rather than for playing.
 */
@Component
@Slf4j
public class GraphRunner {

    /** How much of the bar the fetch owns; the rest is shared by everything after it. */
    private static final double FETCH_SHARE = 0.9;

    public record Output(String nodeId, Path file, String describe) {}

    private final VideoDownloader downloader;
    private final Ffmpeg ffmpeg;
    private final YtDlp ytDlp;

    public GraphRunner(VideoDownloader downloader, Ffmpeg ffmpeg, YtDlp ytDlp) {
        this.downloader = downloader;
        this.ffmpeg = ffmpeg;
        this.ytDlp = ytDlp;
    }

    public List<Output> run(Graph graph, Path into, DownloadProgress progress) throws Exception {
        List<Node> order = graph.inOrder();
        Map<String, Path> produced = new LinkedHashMap<>();
        List<Output> outputs = new ArrayList<>();

        Node.Audio fused = fusedAudio(graph);
        Map<String, Node.Cut> fusedCuts = fusedCuts(graph);
        int steps = (int) order.stream()
                .filter(node -> isWork(node, fused) && !fusedCuts.containsValue(node))
                .count();
        int done = 0;
        int piece = 0;
        int duration = steps == 0 ? 0 : durationOf(graph);

        for (Node node : order) {
            switch (node) {
                case Node.Fetch fetch -> produced.put(fetch.id(),
                        fetch(graph, fetch, fused, into, progress));

                case Node.Audio audio when audio.equals(fused) ->
                        produced.put(audio.id(), produced.get(audio.input()));

                case Node.Cut cut when fusedCuts.containsValue(cut) -> { }

                case Node.Cut cut -> produced.put(cut.id(), ffmpeg.cut(produced.get(cut.input()),
                        cut.window(), ++piece, duration, share(progress, done, steps)));

                case Node.Encode encode when fusedCuts.containsKey(encode.id()) -> {
                    Node.Cut cut = fusedCuts.get(encode.id());
                    produced.put(encode.id(), ffmpeg.cutAndEncode(produced.get(cut.input()),
                            cut.window(), encode.height(), encode.container(), encode.quality(),
                            ++piece, share(progress, done, steps)));
                }

                case Node.Encode encode -> produced.put(encode.id(),
                        ffmpeg.encode(produced.get(encode.input()), encode.height(),
                                encode.container(), encode.quality(), duration,
                                share(progress, done, steps)));

                case Node.Normalize normalize -> produced.put(normalize.id(),
                        ffmpeg.normalizeLoudness(produced.get(normalize.input()), duration,
                                share(progress, done, steps)));

                case Node.Audio audio -> produced.put(audio.id(),
                        ffmpeg.extractAudio(produced.get(audio.input()), audio.format(), duration,
                                share(progress, done, steps)));

                case Node.Concat concat -> produced.put(concat.id(),
                        ffmpeg.concat(concat.inputs().stream().map(produced::get).toList(),
                                share(progress, done, steps)));

                case Node.Publish publish -> outputs.add(new Output(publish.id(),
                        produced.get(publish.input()), label(graph, publish)));
            }
            if (isWork(node, fused)) {
                done++;
                progress.processing(fraction(done, steps));
            }
        }
        keepOnly(produced, outputs);
        return List.copyOf(outputs);
    }

    private Path fetch(Graph graph, Node.Fetch fetch, Node.Audio fused, Path into,
                       DownloadProgress progress) throws Exception {

        if (fused != null && fused.input().equals(fetch.id())) {
            log.info("job files: audio alone, so no video is fetched to be discarded");
            return downloader.audio(fetch.url(), fused.format().extension(), into, progress);
        }
        // What the encode downstream is going to produce, so the fetch can pick a source worth
        // decoding rather than the biggest one on offer.
        Node.Encode encode = encodeFedBy(graph, fetch);
        if (encode == null) {
            return downloader.video(fetch.url(), fetch.maxHeight(), into, progress,
                    MediaInfo.Purpose.DELIVERY);
        }
        Integer target = encode.height() == null ? fetch.maxHeight() : encode.height();
        return downloader.video(fetch.url(), target, into, progress,
                MediaInfo.Purpose.RE_ENCODING);
    }

    /**
     * Cuts whose only consumer is an encode, keyed by that encode.
     *
     * <p>Run as one ffmpeg call the pair is exact to the frame, because re-encoding from an input
     * seek does not have to start on a keyframe. Run in order it is neither exact nor cheaper: the
     * copying cut hands the encoder up to a GOP of material nobody asked for and the encoder pays
     * for it. So this is a fusion that only ever improves the answer, like the audio one above.
     */
    private static Map<String, Node.Cut> fusedCuts(Graph graph) {
        Map<String, Node.Cut> fused = new LinkedHashMap<>();
        for (Node node : graph.nodes()) {
            if (node instanceof Node.Cut cut) {
                List<Node> consumers = consumersOf(graph, cut.id());
                if (consumers.size() == 1 && consumers.getFirst() instanceof Node.Encode encode) {
                    fused.put(encode.id(), cut);
                }
            }
        }
        return fused;
    }

    private static Node.Audio fusedAudio(Graph graph) {
        return graph.source()
                .map(fetch -> consumersOf(graph, fetch.id()))
                .filter(consumers -> consumers.size() == 1)
                .map(List::getFirst)
                .filter(Node.Audio.class::isInstance)
                .map(Node.Audio.class::cast)
                .orElse(null);
    }

    /** The first encode this fetch feeds, however many steps away, or null when it feeds none. */
    private static Node.Encode encodeFedBy(Graph graph, Node.Fetch fetch) {
        List<String> reachable = new ArrayList<>(List.of(fetch.id()));
        for (int i = 0; i < reachable.size(); i++) {
            for (Node consumer : consumersOf(graph, reachable.get(i))) {
                if (consumer instanceof Node.Encode encode) {
                    return encode;
                }
                reachable.add(consumer.id());
            }
        }
        return null;
    }

    private static List<Node> consumersOf(Graph graph, String id) {
        return graph.nodes().stream().filter(node -> node.inputs().contains(id)).toList();
    }

    /**
     * The audio fusion above means the node standing for the work may not be the one that did it,
     * so what a result is called comes from the branch that produced it rather than from its own id.
     */
    private static String label(Graph graph, Node.Publish publish) {
        return graph.byId(publish.input()).describe();
    }

    private int durationOf(Graph graph) {
        return graph.source()
                .flatMap(fetch -> safeProbe(fetch.url()))
                .map(MediaInfo::durationSeconds)
                .orElse(0);
    }

    private Optional<MediaInfo> safeProbe(String url) {
        try {
            return Optional.of(ytDlp.probe(url));
        } catch (Exception e) {
            log.debug("probe for progress reporting failed: {}", e.toString());
            return Optional.empty();
        }
    }

    private static boolean isWork(Node node, Node.Audio fused) {
        return !(node instanceof Node.Fetch) && !(node instanceof Node.Publish)
                && !node.equals(fused);
    }

    private static java.util.function.DoubleConsumer share(DownloadProgress progress, int done,
                                                           int steps) {
        return fraction -> progress.processing(fraction((done + fraction), steps));
    }

    private static double fraction(double done, int steps) {
        return steps == 0 ? 1 : Math.min(1, done / steps);
    }

    /**
     * Everything a graph makes is written into the one working directory, and only the leaves are
     * ever served. The rest is the source and the intermediates, which for a two-cut re-encode is
     * several times the size of what was asked for.
     */
    private void keepOnly(Map<String, Path> produced, List<Output> outputs) {
        List<Path> kept = outputs.stream().map(Output::file).toList();
        produced.values().stream()
                .filter(file -> file != null && !kept.contains(file))
                .forEach(file -> {
                    try {
                        Files.deleteIfExists(file);
                    } catch (Exception e) {
                        log.debug("could not delete the intermediate {}: {}", file, e.toString());
                    }
                });
    }
}
