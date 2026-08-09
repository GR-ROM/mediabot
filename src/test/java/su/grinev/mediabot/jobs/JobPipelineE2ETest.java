package su.grinev.mediabot.jobs;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.mockito.ArgumentCaptor;
import su.grinev.mediabot.links.ShortLink;
import su.grinev.mediabot.links.ShortLinkService;
import su.grinev.mediabot.media.VideoDownloader;
import su.grinev.mediabot.telegram.MediaBot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import su.grinev.mediabot.media.DownloadProgress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The whole service, running: a real Spring context, the real store on a real SQLite file, the real
 * worker threads, the real delivery path.
 *
 * <p>Only the two things that leave the machine are replaced. the downloader becomes a stub that writes a
 * file, because a test that downloads from the internet fails for reasons that have nothing to do
 * with this code; Telegram becomes a mock, because the delivery worth checking is that the file is
 * handed to the transport, not that Telegram accepted it. Everything between the request and that
 * hand-off is the thing under test.
 *
 * <pre>./gradlew test --tests '*JobPipelineE2ETest'</pre>
 */
@SpringBootTest(properties = {
        "mediabot.llm.preflight=false",
        "mediabot.telegram.token=",
        "mediabot.loop.trace-path=",
        "mediabot.jobs.concurrency=2",
        // Nothing is small enough to hand over in the chat, so this stays the test of the link
        // path it has always been. FileDeliveryE2ETest is the one that raises this.
        "mediabot.telegram.send-file-under-bytes=0"
})
class JobPipelineE2ETest {

    private static final long CHAT = 4242;

    private static Path root;

    @DynamicPropertySource
    static void freshWorkingDirectories(DynamicPropertyRegistry registry) {
        root = Path.of("build", "test-work", "e2e-" + System.nanoTime());
        registry.add("mediabot.jobs.database-path", () -> root.resolve("jobs.db").toString());
        registry.add("mediabot.media.work-dir", () -> root.resolve("media").toString());
        registry.add("mediabot.links.dir", () -> root.resolve("public").toString());
        registry.add("mediabot.links.base-url", () -> "http://test.local:8080");
    }

    @MockitoBean
    private VideoDownloader downloader;

    @MockitoBean
    private MediaBot bot;

    @Autowired
    private JobQueue queue;

    @Autowired
    private ShortLinkService shortLinks;

    @Test
    void aQueuedDownloadIsClaimedRunAndHandedToTheChat() throws Exception {
        when(downloader.video(any(), any(), any(), any(), any())).thenAnswer(call -> {
            Path directory = call.getArgument(2);
            DownloadProgress progress = call.getArgument(3);
            progress.downloading(0.5);
            Path file = directory.resolve("video.mp4");
            Files.write(file, new byte[64 * 1024]);
            progress.downloading(1.0);
            progress.processing(1.0);
            return file;
        });

        Job job = queue.enqueue(JobSpec.download(CHAT, "https://example.test/v", 360)
                .withTitle("A video"));
        assertEquals(JobState.PENDING, job.state(), "enqueue must not run anything itself");

        Job finished = awaitFinish(job.id());

        assertEquals(JobState.DONE, finished.state(), "ended as " + finished.state()
                + (finished.error() == null ? "" : ": " + finished.error()));
        assertNotNull(finished.result(), "a DONE job with no file is a job nobody can be sent");
        assertEquals(64 * 1024, finished.sizeBytes());

        // Delivery is a link now, so what the chat gets is a URL and what the disk gets is the
        // file moved out of the job's working directory and into the one being served.
        var said = ArgumentCaptor.forClass(String.class);
        verify(bot, timeout(TimeUnit.SECONDS.toMillis(10)).atLeastOnce())
                .say(eq(CHAT), said.capture());

        String message = said.getAllValues().stream()
                .filter(text -> text.contains("http://test.local:8080/d/"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no link was sent: " + said.getAllValues()));

        ShortLink link = shortLinks.findActiveByCode(codeIn(message)).orElseThrow(
                () -> new AssertionError("the link the user was given resolves to nothing"));
        assertTrue(Files.isRegularFile(link.file()), "the published file is not on disk");
        assertEquals(64 * 1024, Files.size(link.file()));
        assertEquals(link.file(), queue.find(job.id()).orElseThrow().result(),
                "the job should point at where its file actually is now");
        assertFalse(Files.exists(root.resolve("media").resolve("job-" + job.id())),
                "the file was copied instead of moved, so the job directory keeps a second copy");
    }

    private static String codeIn(String message) {
        int at = message.indexOf("/d/");
        String rest = message.substring(at + 3);
        int end = rest.indexOf('\n');
        return end < 0 ? rest : rest.substring(0, end).strip();
    }

    @Test
    void cancellingStopsTheDownloadThatIsAlreadyRunning() throws Exception {
        CountDownLatch downloading = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();

        when(downloader.video(any(), any(), any(), any(), any())).thenAnswer(call -> {
            downloading.countDown();
            try {
                Thread.sleep(TimeUnit.MINUTES.toMillis(5));
            } catch (InterruptedException e) {
                interrupted.set(true);
                throw e;
            }
            return null;
        });

        Job job = queue.enqueue(JobSpec.download(CHAT, "https://example.test/slow", 360));

        assertTrue(downloading.await(10, TimeUnit.SECONDS), "no worker ever picked the job up");

        Job cancelled = queue.cancel(CHAT, job.id()).orElseThrow();

        assertEquals(JobState.CANCELLED, cancelled.state());
        assertTrue(waitFor(interrupted::get), "the download was left running after the cancel");
        assertEquals(JobState.CANCELLED, queue.find(job.id()).orElseThrow().state(),
                "the worker must not write over a cancelled job on its way out");
    }

    private Job awaitFinish(long jobId) {
        assertTrue(waitFor(() -> queue.find(jobId).map(j -> j.state().isFinished()).orElse(false)),
                "job " + jobId + " never finished");
        return queue.find(jobId).orElseThrow();
    }

    private boolean waitFor(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
