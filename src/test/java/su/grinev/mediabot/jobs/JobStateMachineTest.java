package su.grinev.mediabot.jobs;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import su.grinev.mediabot.media.DownloadProgress;
import su.grinev.mediabot.media.VideoDownloader;
import su.grinev.mediabot.telegram.MediaBot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The walk from PENDING to EXPIRED, driven by nothing but the code.
 *
 * <p>Every move is made by a worker or by the link expiring. No tool writes a state and no answer
 * from the model can: the model's whole part ends when the job is queued.
 */
@SpringBootTest(properties = {
        "mediabot.llm.preflight=false",
        "mediabot.telegram.token=",
        "mediabot.loop.trace-path=",
        "mediabot.jobs.concurrency=1"
})
class JobStateMachineTest {

    private static final long CHAT = 4242;

    @DynamicPropertySource
    static void freshDirectories(DynamicPropertyRegistry registry) {
        Path root = Path.of("build", "test-work", "states-" + System.nanoTime());
        registry.add("mediabot.jobs.database-path", () -> root.resolve("jobs.db").toString());
        registry.add("mediabot.media.work-dir", () -> root.resolve("media").toString());
        registry.add("mediabot.links.dir", () -> root.resolve("public").toString());
    }

    @MockitoBean
    private MediaBot bot;

    @MockitoBean
    private VideoDownloader downloader;

    @Autowired
    private JobQueue queue;

    @Test
    void aJobWalksPendingDownloadingProcessingDone() throws Exception {
        CountDownLatch downloading = new CountDownLatch(1);
        CountDownLatch releaseProcessing = new CountDownLatch(1);
        List<JobState> seen = new CopyOnWriteArrayList<>();

        when(downloader.video(any(), any(), any(), any(), any())).thenAnswer(call -> {
            Path directory = call.getArgument(2);
            DownloadProgress progress = call.getArgument(3);
            progress.downloading(0.5);
            downloading.countDown();
            releaseProcessing.await(10, TimeUnit.SECONDS);
            progress.processing(0.5);
            Path file = directory.resolve("video.mp4");
            Files.write(file, new byte[4096]);
            return file;
        });

        Job job = queue.enqueue(JobSpec.download(CHAT, "https://example.test/v", 360));
        assertEquals(JobState.PENDING, job.state(), "queueing must not run anything");

        assertTrue(downloading.await(10, TimeUnit.SECONDS), "no worker claimed the job");
        seen.add(queue.find(job.id()).orElseThrow().state());

        releaseProcessing.countDown();
        assertTrue(waitFor(() -> queue.find(job.id()).orElseThrow().state().isFinished()),
                "job never finished");
        seen.add(queue.find(job.id()).orElseThrow().state());

        assertEquals(List.of(JobState.DOWNLOADING, JobState.DONE), seen);
    }

    @Test
    void processingIsAStateOfItsOwnWhileFfmpegRuns() throws Exception {
        CountDownLatch processing = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);

        when(downloader.video(any(), any(), any(), any(), any())).thenAnswer(call -> {
            Path directory = call.getArgument(2);
            DownloadProgress progress = call.getArgument(3);
            progress.downloading(1.0);
            progress.processing(0.1);
            processing.countDown();
            finish.await(10, TimeUnit.SECONDS);
            Path file = directory.resolve("video.mp4");
            Files.write(file, new byte[4096]);
            return file;
        });

        Job job = queue.enqueue(JobSpec.download(CHAT, "https://example.test/merge", 360));

        assertTrue(processing.await(10, TimeUnit.SECONDS), "the merge phase never started");
        assertEquals(JobState.PROCESSING, queue.find(job.id()).orElseThrow().state(),
                "a job whose bytes are all in and whose ffmpeg is running is not DOWNLOADING");

        finish.countDown();
        assertTrue(waitFor(() -> queue.find(job.id()).orElseThrow().state() == JobState.DONE));
    }

    @Test
    void aLinkThatDiesTakesItsJobToExpired() throws Exception {
        Job job = queue.enqueue(JobSpec.download(CHAT, "https://example.test/expiring", 360));
        Job done = queue.updateResult(job.id(), Path.of("build", "test-work", "gone.mp4"), 10);
        assertEquals(JobState.DONE, done.state());

        Job expired = queue.expire(job.id());

        assertEquals(JobState.EXPIRED, expired.state());
        assertTrue(expired.state().isFinished());
    }

    private boolean waitFor(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
