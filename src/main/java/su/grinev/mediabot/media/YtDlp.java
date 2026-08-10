package su.grinev.mediabot.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import su.grinev.mediabot.AgentProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The yt-dlp command line, wrapped.
 *
 * <p>yt-dlp is delegated to rather than reimplemented for the reason insta-dl already gives: these
 * sites change their markup constantly, and yt-dlp is the thing that keeps up with them. What is
 * added here is the part a bot needs and a CLI does not — a size known before the download, progress
 * while it runs, and failures turned into sentences a person can act on.
 */
@Component
@Slf4j
public class YtDlp {

    private static final Pattern PROGRESS = Pattern.compile("\\[download]\\s+(\\d+\\.?\\d*)%");

    /** Where a provider that has stopped answering is reported. Null in a test that has nobody. */
    private final org.springframework.context.ApplicationEventPublisher events;

    private final ObjectMapper mapper;
    private final Path binary;
    private final Path ffmpeg;
    private final Duration probeTimeout;
    private final Duration downloadTimeout;
    private final Path cookiesFile;
    private final String cookiesFromBrowser;
    private final String jsRuntime;
    private final Path plugins;
    private final String potProvider;

    /**
     * The one failure the owner has to do something about, so it is named once and matched on that
     * name rather than on a phrase repeated in two places.
     */
    public static final String NEEDS_AUTHENTICATION =
            "the host wants proof this is not a bot — it needs cookies from a signed-in "
                    + "account configured for yt-dlp";

    public YtDlp(ObjectMapper mapper, AgentProperties props,
                 org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
        this.mapper = mapper;
        this.binary = props.media().ytDlp();
        this.ffmpeg = props.media().ffmpeg();
        this.probeTimeout = Duration.ofSeconds(props.media().probeTimeoutSeconds());
        this.downloadTimeout = Duration.ofSeconds(props.media().downloadTimeoutSeconds());
        this.cookiesFile = props.media().cookiesFile();
        this.cookiesFromBrowser = props.media().cookiesFromBrowser();
        this.jsRuntime = props.media().jsRuntime();
        this.plugins = props.media().ytDlpPlugins();
        this.potProvider = props.media().potProvider();
        if (props.media().hasPotProvider()) {
            log.info("proof-of-origin tokens from {}", potProvider);
        } else if (!props.media().hasCookies()) {
            log.warn("no proof-of-origin provider and no cookies — YouTube will refuse most videos "
                    + "with \"Sign in to confirm you're not a bot\". Set mediabot.media.pot-provider, "
                    + "which needs no account, or mediabot.media.cookies-file, which does.");
        }
        if (!props.media().hasJsRuntime()) {
            log.warn("no JS runtime configured — YouTube hands out an \"n challenge\" that has to "
                    + "be executed, and without one every video comes back with no formats at all. "
                    + "Set mediabot.media.js-runtime, e.g. node:/path/to/node.");
        }
    }

    /**
     * Adds the authentication and the JavaScript runtime.
     *
     * <p>Applied to every invocation including {@code probe}, because both failures happen at the
     * metadata step: cookies on the download alone would leave the bot unable to say what a video
     * even is, and a missing runtime produces an empty format list rather than an error.
     *
     * <p>These two together are the whole of what YouTube currently requires. Neither is optional
     * for it and neither is needed for anything else, which is why both are configuration rather
     * than something baked in.
     */
    private void withAccess(List<String> command) {
        if (cookiesFile != null) {
            command.add("--cookies");
            command.add(cookiesFile.toString());
        } else if (cookiesFromBrowser != null && !cookiesFromBrowser.isBlank()) {
            command.add("--cookies-from-browser");
            command.add(cookiesFromBrowser);
        }
        if (jsRuntime != null && !jsRuntime.isBlank()) {
            // "node" alone works when the binary is on the PATH the process actually inherits,
            // which on Windows is not the one a shell shows you. "node:<path>" always works.
            command.add("--js-runtimes");
            command.add(jsRuntime);
        }
        // The proof-of-origin token, which is what makes an account unnecessary. Named explicitly
        // rather than left to the default plugin directories: those differ between an installed
        // yt-dlp and the standalone binary this runs, and a plugin that silently is not loaded
        // looks exactly like one that does not work.
        if (plugins != null) {
            command.add("--plugin-dirs");
            command.add(plugins.toString());
        }
        if (potProvider != null && !potProvider.isBlank()) {
            command.add("--extractor-args");
            command.add("youtubepot-bgutilhttp:base_url=" + potProvider.strip());
        }
    }

    /** A failure of the download itself, already worded for a person. */
    public static class Failed extends IOException {
        public Failed(String message) {
            super(message);
        }
    }

    /**
     * The one place a run is judged, and the only place its output is repeated in full.
     *
     * <p>Two things happen here that used to happen nowhere. The whole of what the process said goes
     * to the log, always, with the url beside it — that is what somebody reads afterwards, and
     * pasting it into a chat instead is how a person is handed a traceback for an answer. And the
     * plugin's own complaints are noticed: a proof-of-origin provider that has died says so in a
     * warning on a run that otherwise succeeds, which is the only warning this bot gets before the
     * downloads start failing days later.
     *
     * @throws Failed when the process failed, worded for a person
     */
    private void checked(ProcessRunner.Result result, String url) throws Failed {
        String said = result.stderrText();
        if (said.contains(POT_MARKER)) {
            String detail = said.lines()
                    .filter(line -> line.contains(POT_MARKER))
                    .findFirst()
                    .orElse(said);
            log.warn("the proof-of-origin provider is not answering: {}", detail);
            if (events != null) {
                events.publishEvent(new ProviderUnreachable(detail));
            }
        }
        if (result.exitCode() == 0) {
            return;
        }
        // The whole thing, once, here. What the person gets is the sentence below it.
        log.warn("yt-dlp failed on {}:{}{}", url, System.lineSeparator(), said);
        throw new Failed(explain(said));
    }

    /** What the plugin prefixes its own complaints with, which is how they are told from the rest. */
    private static final String POT_MARKER = "[pot:";

    /**
     * What the host says about a video. Seconds, no bytes transferred.
     *
     * @param url already checked by {@link UrlGuard}
     */
    public MediaInfo probe(String url) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
                binary.toString(),
                "--no-playlist",
                "-J"));
        withAccess(command);
        command.add(url);
        var result = ProcessRunner.run(command, probeTimeout);
        checked(result, url);
        return parse(mapper.readTree(result.stdoutText()));
    }

    /**
     * Downloads into {@code intoDirectory} and returns the file produced.
     *
     * @param maxHeight ceiling on video height, or null for the best available
     * @param progress called with 0..1 as it goes, or null
     */
    public Path download(String url, Integer maxHeight, Path intoDirectory, DoubleConsumer progress)
            throws IOException, InterruptedException {

        List<String> command = new ArrayList<>(List.of(
                binary.toString(),
                "--no-playlist",
                "--newline",          // progress on its own lines rather than redrawn in place
                "--no-part",          // no .part file left behind when something goes wrong
                "--windows-filenames",
                // The second line of defence against a live stream, and it earned its place: a
                // stream has no end, so downloading one runs until the timeout kills it half an
                // hour later, holding a worker the whole time. DownloadRequests refuses these up
                // front, but only when probing succeeded — and a probe that timed out is exactly
                // when something would slip through.
                "--match-filter", "!is_live",
                "-f", videoSelector(maxHeight),
                // Muxing separate video and audio streams is ffmpeg's job; asking for mp4 out of it
                // is what makes the result playable in Telegram's own player rather than a download.
                "--merge-output-format", "mp4",
                "-o", intoDirectory.resolve("%(title).80B [%(id)s].%(ext)s").toString()));
        withFfmpeg(command);
        withAccess(command);
        command.add(url);

        return runAndCollect(command, url, intoDirectory, progress);
    }

    public Path downloadFormat(String url, String formatId, Path target, DoubleConsumer progress)
            throws IOException, InterruptedException {

        List<String> command = new ArrayList<>(List.of(
                binary.toString(),
                "--no-playlist",
                "--newline",
                "--no-part",
                "--match-filter", "!is_live",
                "-f", formatId,
                "-o", target.toString()));
        withAccess(command);
        command.add(url);

        var result = ProcessRunner.run(command, downloadTimeout,
                line -> reportProgress(line, progress));
        try {
            checked(result, url);
        } catch (IOException e) {
            deleteQuietly(target);
            throw e;
        }
        if (!Files.isRegularFile(target) || Files.size(target) == 0) {
            deleteQuietly(target);
            log.warn("yt-dlp reported success on {} but stream {} produced no file:{}{}",
                    url, formatId, System.lineSeparator(), result.stderrText());
            throw new Failed("the download finished without producing a file");
        }
        return target;
    }

    /**
     * Downloads the audio track alone.
     *
     * @param format the container to extract into, such as {@code m4a} or {@code mp3}
     */
    public Path downloadAudio(String url, String format, Path intoDirectory,
                              DownloadProgress progress)
            throws IOException, InterruptedException {

        List<String> command = new ArrayList<>(List.of(
                binary.toString(),
                "--no-playlist",
                "--newline",
                "--no-part",
                "--windows-filenames",
                "-f", "bestaudio/best",
                "-x", "--audio-format", format,
                "-o", intoDirectory.resolve("%(title).80B [%(id)s].%(ext)s").toString()));
        withFfmpeg(command);
        withAccess(command);
        command.add(url);

        // The extraction runs inside the same yt-dlp process, after the download, and announces
        // itself on stdout. Without watching for it the job sits in DOWNLOADING while ffmpeg works.
        var extracting = new java.util.concurrent.atomic.AtomicBoolean();
        var result = ProcessRunner.run(command, downloadTimeout, line -> {
            if (line.startsWith("[ExtractAudio]") || line.startsWith("[Merger]")) {
                extracting.set(true);
                progress.processing(0);
            }
            reportProgress(line, extracting.get() ? progress::processing : progress::downloading);
        });
        checked(result, url);
        return newestFile(intoDirectory, result);
    }

    /**
     * The entries of a playlist, without downloading any of them.
     *
     * @param limit how many to list at most
     */
    public List<PlaylistItem> playlistItems(String url, int limit)
            throws IOException, InterruptedException {

        List<String> command = new ArrayList<>(List.of(
                binary.toString(),
                "--flat-playlist",
                "-J",
                "--playlist-end", String.valueOf(Math.max(1, limit))));
        withAccess(command);
        command.add(url);
        var result = ProcessRunner.run(command, probeTimeout);
        checked(result, url);
        JsonNode root = mapper.readTree(result.stdoutText());
        List<PlaylistItem> items = new ArrayList<>();
        int index = 1;
        for (JsonNode entry : root.path("entries")) {
            String entryUrl = entry.path("url").asText("");
            if (entryUrl.isBlank()) {
                continue;
            }
            items.add(new PlaylistItem(index++, entry.path("title").asText("(untitled)"),
                    entryUrl, entry.path("duration").asInt(0)));
            if (items.size() >= limit) {
                break;
            }
        }
        return items;
    }

    /** @param index position in the playlist, 1-based, as a person would count them */
    public record PlaylistItem(int index, String title, String url, int durationSeconds) {}

    private Path runAndCollect(List<String> command, String url, Path intoDirectory,
                               DoubleConsumer progress)
            throws IOException, InterruptedException {

        var result = ProcessRunner.run(command, downloadTimeout,
                line -> reportProgress(line, progress));
        checked(result, url);
        return newestFile(intoDirectory, result);
    }

    private Path newestFile(Path intoDirectory, ProcessRunner.Result result) throws IOException {
        try (Stream<Path> files = Files.list(intoDirectory)) {
            return files.filter(Files::isRegularFile)
                    .max((a, b) -> Long.compare(sizeOf(a), sizeOf(b)))
                    .orElseThrow(() -> new Failed(
                            "yt-dlp reported success but produced no file — "
                                    + explain(result.stderrText())));
        }
    }

    /**
     * Points yt-dlp at the ffmpeg we were configured with.
     *
     * <p>Without this it looks for one on PATH, and a machine with no ffmpeg fails at the very end of
     * a long download, at the muxing step — the most expensive moment to discover a missing binary.
     */
    private void withFfmpeg(List<String> command) {
        if (ffmpeg != null && Files.isRegularFile(ffmpeg)) {
            command.add("--ffmpeg-location");
            command.add(ffmpeg.toString());
        }
    }

    /**
     * The format expression for a height ceiling.
     *
     * <p>The fallbacks after each slash matter: a host with no separate streams, or none tagged with
     * a height, would otherwise produce "requested format not available" for a video that is sitting
     * right there.
     */
    static String videoSelector(Integer maxHeight) {
        if (maxHeight == null) {
            return "bestvideo+bestaudio/best";
        }
        return ("bestvideo[height<=%d]+bestaudio/best[height<=%d]/bestvideo+bestaudio/best")
                .formatted(maxHeight, maxHeight);
    }

    static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("could not delete {}: {}", file, e.toString());
        }
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    private static void reportProgress(String line, DoubleConsumer progress) {
        if (progress == null) {
            return;
        }
        Matcher m = PROGRESS.matcher(line);
        if (m.find()) {
            progress.accept(Double.parseDouble(m.group(1)) / 100.0);
        }
    }

    private MediaInfo parse(JsonNode root) {
        List<MediaInfo.Format> formats = new ArrayList<>();
        for (JsonNode f : root.path("formats")) {
            String id = f.path("format_id").asText("");
            if (id.isBlank()) {
                continue;
            }
            long size = f.path("filesize").asLong(0);
            if (size <= 0) {
                size = f.path("filesize_approx").asLong(0);
            }
            formats.add(new MediaInfo.Format(
                    id,
                    f.path("ext").asText(""),
                    f.path("width").asInt(0),
                    f.path("height").asInt(0),
                    f.path("fps").asDouble(0),
                    size,
                    f.path("vcodec").asText("none"),
                    f.path("acodec").asText("none")));
        }
        return new MediaInfo(
                root.path("title").asText("(untitled)"),
                root.path("uploader").asText(""),
                root.path("duration").asInt(0),
                root.path("is_live").asBoolean(false),
                formats);
    }

    /**
     * Turns yt-dlp's stderr into one sentence somebody can act on.
     *
     * <p>The failures below are the ones that recur, and each has a different answer for the person
     * asking: geo-blocking cannot be worked around from here, a private video never will be, and a
     * login wall needs cookies configured. Handing back the raw stderr instead — several lines of
     * traceback and a URL — reliably reads as "the bot is broken".
     */
    static String explain(String stderr) {
        String lower = stderr.toLowerCase(Locale.ROOT);
        if (lower.contains("sign in to confirm") || lower.contains("confirm you're not a bot")) {
            return NEEDS_AUTHENTICATION;
        }
        if (lower.contains("private video") || lower.contains("this video is private")) {
            return "the video is private — no downloader can get it";
        }
        if (lower.contains("members-only") || lower.contains("join this channel")) {
            return "the video is for channel members only";
        }
        // Matched on the fragment rather than the whole sentence: yt-dlp says "The uploader has not
        // made this video available in your country", which does not contain "not available in your
        // country" at all — the negation sits earlier in the sentence.
        if (lower.contains("available in your country") || lower.contains("in your location")
                || (lower.contains("geo") && lower.contains("block"))) {
            return "the video is blocked in the country this bot connects from";
        }
        if (lower.contains("video unavailable") || lower.contains("has been removed")) {
            return "the video has been removed or is unavailable";
        }
        if (lower.contains("age") && lower.contains("restrict")) {
            return "the video is age-restricted — it needs cookies from a signed-in account";
        }
        if (lower.contains("requested format is not available")) {
            return "this video does not have the quality that was asked for";
        }
        if (lower.contains("unsupported url")) {
            return "yt-dlp cannot download from this site";
        }
        // What --match-filter !is_live produces. Without translating it, a live stream comes back
        // as "does not pass filter", which means nothing to anybody.
        if (lower.contains("does not pass filter") && lower.contains("is_live")) {
            return "this is a live stream — it has no end, so it cannot be downloaded whole";
        }
        if (lower.contains("ffmpeg") && lower.contains("not found")) {
            return "ffmpeg was not found, and it is needed to join the video and audio streams";
        }
        // Nothing recognised. What used to happen here was the last line of stderr, handed to
        // whoever sent the link — which is a traceback, a URL and a stack of warnings presented as
        // an answer, and reads as the bot being broken rather than the video being unavailable. The
        // whole of it is in the log; this is the part a person gets.
        return UNRECOGNISED;
    }

    /** What is said when the reason is not one of the ones worth naming. */
    public static final String UNRECOGNISED = "the download failed — I have written down why";
}
