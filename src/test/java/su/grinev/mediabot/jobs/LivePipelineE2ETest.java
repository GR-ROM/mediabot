package su.grinev.mediabot.jobs;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import su.grinev.mediabot.telegram.MediaBot;
import su.grinev.mediabot.text.Sizes;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * The service against a real link, for poking it by hand.
 *
 * <p>Everything is real except the chat transport: the URL goes through the guard, the probe, the
 * upscale rule, the queue and a worker thread running an actual yt-dlp, and the file is handed to a
 * mocked {@link MediaBot} at the end so nothing needs a Telegram token. What it answers is what the
 * bot would have sent somebody.
 *
 * <pre>
 * ./gradlew test -Dmediabot.live=https://www.youtube.com/watch?v=jNQXAC9IVRw --tests '*LivePipelineE2ETest'
 * ./gradlew test -Dmediabot.live=&lt;url&gt; -Dmediabot.cookies=cookies.txt -Dmediabot.js=node --tests '*LivePipelineE2ETest'
 * </pre>
 *
 * <p>The URL's host is added to the allowlist for the run, so any link can be tried without editing
 * the configuration.
 */
@EnabledIfSystemProperty(named = "mediabot.live", matches = "http.+")
@SpringBootTest(properties = {
        "mediabot.llm.preflight=false",
        "mediabot.telegram.token=",
        "mediabot.loop.trace-path=",
        "mediabot.jobs.concurrency=1"
})
class LivePipelineE2ETest {

    private static final long CHAT = 4242;

    /** Below anything a real video offers, so the run does not depend on which link was given. */
    private static final int TRANSCODE_HEIGHT = 144;

    @DynamicPropertySource
    static void liveSetup(DynamicPropertyRegistry registry) {
        Path root = Path.of("build", "live-e2e");
        registry.add("mediabot.jobs.database-path", () -> root.resolve("jobs.db").toString());
        registry.add("mediabot.media.work-dir", () -> root.resolve("media").toString());
        registry.add("mediabot.media.allowed-hosts[0]", () -> URI.create(url()).getHost());
        registry.add("mediabot.media.cookies-file", () -> System.getProperty("mediabot.cookies", ""));
        registry.add("mediabot.media.js-runtime", () -> System.getProperty("mediabot.js", ""));
    }

    private static String url() {
        return System.getProperty("mediabot.live");
    }

    @MockitoBean
    private MediaBot bot;

    @Autowired
    private DownloadRequests requests;

    @Autowired
    private JobQueue queue;

    @Test
    void aRealLinkGoesThroughTheWholeServiceAndComesOutAsAFile() throws Exception {
        // Best available rather than a fixed height: this test takes whatever link it is given,
        // and asking for 360p of a 240p video is refused on purpose — LiveMediaTest covers that.
        var outcome = requests.submit(JobSpec.download(CHAT, url(), null));

        Job queued = switch (outcome) {
            case DownloadRequests.Outcome.Refused(String reason) -> throw new AssertionError("the service refused the link: " + reason);
            case DownloadRequests.Outcome.Queued(var job, var info, var estimatedBytes) -> {
                System.out.printf("queued job %d: %s%n", job.id(), job.describe());
                info.ifPresent(i -> System.out.printf("probed:    %s, %ds, heights %s%n", i.title(), i.durationSeconds(), i.heights()));
                estimatedBytes.ifPresent(size -> System.out.printf("estimate:  %s%n", Sizes.bytes(size)));
                yield job;
            }
        };

        Job finished = awaitFinish(queued.id());

        System.out.printf("finished:  %s%n", finished.state());
        System.out.printf("file:      %s (%s)%n", finished.result(),
                Sizes.bytes(finished.sizeBytes()));

        assertEquals(JobState.DONE, finished.state(), finished.error() == null ? "" : "the download failed: " + finished.error());
        assertNotNull(finished.result());
        assertTrue(Files.isRegularFile(finished.result()));
        assertTrue(finished.sizeBytes() > 10_000, "a file this small is an error page, not a video: " + finished.sizeBytes());

        assertLinkWasPosted();
    }

    /**
     * The scenario that uses both binaries in one job: yt-dlp for the source, then ffmpeg to
     * encode it down. Its own test because it is the only path where the file that is delivered is
     * not the file that was downloaded.
     */
    @Test
    void aTranscodeDownloadsTheSourceAndReEncodesItSmaller() throws Exception {
        var outcome = requests.submit(JobSpec.transcode(CHAT, url(), TRANSCODE_HEIGHT));

        Job queued = switch (outcome) {
            case DownloadRequests.Outcome.Refused(String reason) ->
                    throw new AssertionError("the service refused the transcode: " + reason);
            case DownloadRequests.Outcome.Queued(var job, var info, _) -> {
                System.out.printf("queued job %d for a %dp re-encode%n", job.id(), TRANSCODE_HEIGHT);
                info.ifPresent(i -> System.out.printf("source:    %s, heights %s%n",
                        i.title(), i.heights()));
                yield job;
            }
        };

        Job finished = awaitFinish(queued.id());

        System.out.printf("finished:  %s%n", finished.state());
        System.out.printf("file:      %s (%s)%n", finished.result(),
                Sizes.bytes(finished.sizeBytes()));

        assertEquals(JobState.DONE, finished.state(),
                finished.error() == null ? "" : "the transcode failed: " + finished.error());
        assertNotNull(finished.result());
        assertTrue(Files.isRegularFile(finished.result()));
        assertTrue(finished.result().getFileName().toString().contains(TRANSCODE_HEIGHT + "p"),
                "the delivered file should be the re-encoded one: " + finished.result());
        assertLinkWasPosted();
    }

    private void assertLinkWasPosted() {
        var said = ArgumentCaptor.forClass(String.class);
        verify(bot, timeout(TimeUnit.SECONDS.toMillis(30)).atLeastOnce())
                .say(eq(CHAT), said.capture());
        assertTrue(said.getAllValues().stream().anyMatch(text -> text.contains("/d/")),
                "no link reached the chat: " + said.getAllValues());
    }

    private Job awaitFinish(long jobId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
        JobState reported = null;
        while (System.nanoTime() < deadline) {
            Job job = queue.find(jobId).orElseThrow();
            if (job.state() != reported) {
                reported = job.state();
                System.out.printf("state:     %s%n", reported);
            }
            if (job.state().isFinished()) {
                return job;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("job " + jobId + " was still running after 10 minutes");
    }
}
