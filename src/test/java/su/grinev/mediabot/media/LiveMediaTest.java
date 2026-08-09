package su.grinev.mediabot.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import su.grinev.mediabot.AgentProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real thing: a real yt-dlp, a real host, a real file on disk.
 *
 * <p>Opt-in, because it needs the network and the binaries and takes seconds rather than
 * milliseconds. Everything the ordinary suite covers is a claim about the code; this is the only
 * place that checks the claims match what a video hosting service actually sends back — which is
 * the half that changes without anybody touching this repository.
 *
 * <pre>./gradlew test -Dmediabot.live=https://www.youtube.com/watch?v=jNQXAC9IVRw --tests '*LiveMediaTest'</pre>
 */
@EnabledIfSystemProperty(named = "mediabot.live", matches = "http.+")
class LiveMediaTest {

    private final AgentProperties props = liveProps();
    private final YtDlp ytDlp = new YtDlp(new ObjectMapper(), props);
    private final VideoDownloader downloader =
            new VideoDownloader(ytDlp, new Ffmpeg(props), new ProbeCache(ytDlp));

    private static String url() {
        return System.getProperty("mediabot.live");
    }

    /**
     * An optional {@code -Dmediabot.cookies=path}.
     *
     * <p>Here because YouTube refuses an anonymous request outright, so without one this test can
     * only ever be run against hosts that do not care. The same file the deployed bot uses.
     */
    private static Path cookiesFile() {
        String path = System.getProperty("mediabot.cookies", "");
        return path.isBlank() ? null : Path.of(path);
    }

    @Test
    void theHostAnswersWithSomethingWorthShowingAPerson() throws Exception {
        MediaInfo info = ytDlp.probe(url());

        System.out.printf("title:    %s%n", info.title());
        System.out.printf("uploader: %s%n", info.uploader());
        System.out.printf("duration: %d s%n", info.durationSeconds());
        System.out.printf("live:     %s%n", info.isLive());
        System.out.printf("formats:  %d%n", info.formats().size());
        System.out.printf("heights:  %s%n", info.heights());
        info.heights().forEach(h -> System.out.printf("  %dp -> %s%n", h,
                info.estimatedSize(h).map(String::valueOf).orElse("no figure")));

        assertFalse(info.title().isBlank(), "a title is the one thing every host publishes");
        assertFalse(info.formats().isEmpty(), "no formats means nothing can be downloaded");
        assertTrue(info.maxHeight() > 0, "a video with no height would make the upscale check inert");
    }

    /**
     * The central guarantee, against real numbers.
     *
     * <p>The unit test proves the rule holds over a hand-built {@link MediaInfo}. This proves the
     * rule is fed the truth — that the heights parsed out of a real yt-dlp response are the heights
     * the host actually offers.
     */
    @Test
    void askingForMoreThanARealVideoHasIsRefused() throws Exception {
        MediaInfo info = ytDlp.probe(url());
        var requests = new su.grinev.mediabot.jobs.DownloadRequests(null, null, null);

        int aboveEverything = info.maxHeight() + 720;
        String refusal = requests.upscaleRefusal(aboveEverything, info);

        System.out.printf("asked for %dp of a %dp source -> %s%n",
                aboveEverything, info.maxHeight(), refusal);

        assertNotNull(refusal, "asking above a real source's ceiling has to be refused");
        assertTrue(refusal.contains(info.maxHeight() + "p"));
        assertNull(requests.upscaleRefusal(info.maxHeight(), info),
                "asking for exactly what the source has must still go through");
    }

    @Test
    void aRealDownloadProducesAPlayableFileAndReportsProgress() throws Exception {
        Path into = Files.createTempDirectory("mediabot-live-");
        var progressReports = new AtomicInteger();

        // 360p: enough to exercise the muxing path without spending minutes on bytes.
        var processingReports = new AtomicInteger();
        Path file = downloader.video(url(), 360, into, DownloadProgress.of(
                fraction -> progressReports.incrementAndGet(),
                fraction -> processingReports.incrementAndGet()));

        long size = Files.size(file);
        System.out.printf("downloaded %s (%d bytes) after %d progress reports%n",
                file.getFileName(), size, progressReports.get());

        assertTrue(Files.isRegularFile(file));
        assertTrue(size > 10_000, "a file this small is an error page, not a video: " + size);
        assertTrue(progressReports.get() > 0,
                "no progress callbacks means the chat would show a frozen bar for the whole download");
        assertTrue(processingReports.get() > 0,
                "the merge has to report too, or the job never leaves DOWNLOADING");
        try (var left = Files.list(into)) {
            assertEquals(1, left.filter(Files::isRegularFile).count(),
                    "the separate streams have to be cleaned up, not left doubling the disk use");
        }

        // Cleaned up here rather than left for the OS: these are real megabytes.
        try (var tree = Files.walk(into)) {
            tree.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // best effort
                }
            });
        }
    }

    private static AgentProperties liveProps() {
        return new AgentProperties(
                new AgentProperties.Llm("http://localhost/v1", "", "", "m", 1024, 0.1, 30, false),
                new AgentProperties.Telegram("", "", "", 50L * 1024 * 1024, 0, List.of(), List.of()),
                new AgentProperties.Media(Path.of("bin/yt-dlp.exe"), Path.of("bin/ffmpeg.exe"),
                        Path.of("build/live-work"),
                        List.of("youtube.com", "youtu.be", "instagram.com", "rutube.ru", "commondatastorage.googleapis.com"), 60, 600, cookiesFile(), null, System.getProperty("mediabot.js", ""), 720, 2160),
                new AgentProperties.Links("http://localhost:8080", Path.of("build/test-public"), 24),
                new AgentProperties.Jobs(Path.of("build/live-jobs.db"), 1, 5, 24));
    }
}
