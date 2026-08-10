package su.grinev.mediabot.jobs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import su.grinev.mediabot.Fixtures;
import su.grinev.mediabot.db.Database;
import su.grinev.mediabot.graph.GraphRunner;
import su.grinev.mediabot.media.MediaStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a worker does with a claimed row, which is the part of the queue nothing else can report on.
 *
 * <p>Driven through the real store rather than a mocked one: the state a job ends in is a row, and a
 * test that asserted on an object in memory would pass while the table said something else — which
 * is the exact failure the whole queue design exists to prevent.
 */
class JobWorkersTest {

    private static final long CHAT = 600000002L;
    private static final String URL = "https://www.youtube.com/watch?v=jNQXAC9IVRw";

    /** What a local Bot API server takes, which is the largest anything here is compared against. */
    private static final long NO_LIMIT = 2L * 1024 * 1024 * 1024;

    private Database database;
    private JobStore store;
    private MediaStore media;
    private GraphRunner runner;
    private JobWorkers workers;
    private final List<Job> announced = new CopyOnWriteArrayList<>();
    private final List<Double> reported = new CopyOnWriteArrayList<>();
    private Path workDirectory;

    @BeforeEach
    void setUp(@TempDir Path temp) throws Exception {
        database = Database.inMemory();
        store = JobStore.on(database, 48);
        // No prepare() here: it is the startup sweep, and directoryFor creates what it needs. A
        // fresh temporary directory has nothing to sweep anyway.
        media = new MediaStore(Fixtures.builder().workDir(temp.resolve("work")).build());
        workDirectory = temp.resolve("work");
        runner = mock(GraphRunner.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (workers != null) {
            workers.shutdown();
        }
        database.close();
    }

    /** Starts the workers with a ceiling on what may be handed over, and records every outcome. */
    private void start(long maxUploadBytes) {
        ApplicationEventPublisher events = event -> {
            if (event instanceof JobFinished finished) {
                announced.add(finished.job());
            }
        };
        ProgressSink sink = new ProgressSink() {
            @Override
            public void started(Job job) {
            }

            @Override
            public void progress(Job job, double fraction) {
                reported.add(fraction);
            }

            @Override
            public void finished(Job job) {
            }
        };
        workers = new JobWorkers(store, runner, media, events, provider(sink),
                Fixtures.builder().uploads(50L * 1024 * 1024, maxUploadBytes).build());
        workers.start();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ProgressSink> provider(ProgressSink sink) {
        ObjectProvider<ProgressSink> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any(Supplier.class))).thenReturn(sink);
        return provider;
    }

    private Job queue() {
        return store.create(JobSpec.download(CHAT, URL, null));
    }

    /**
     * Writes the files when the run happens and not before.
     *
     * <p>{@code directoryFor} clears the directory as it hands it out — which is what makes a retry
     * start clean — so anything written ahead of time is gone by the time the runner is called.
     */
    private void runProduces(int... sizes) throws Exception {
        when(runner.run(any(), any(), any())).thenAnswer(call -> produce(sizes));
    }

    private List<GraphRunner.Output> produce(int... sizes) throws Exception {
        List<GraphRunner.Output> produced = new ArrayList<>();
        Path directory = workDirectory.resolve("job-1");
        Files.createDirectories(directory);
        for (int i = 0; i < sizes.length; i++) {
            Path file = directory.resolve((char) ('a' + i) + ".mp4");
            Files.write(file, new byte[sizes[i]]);
            produced.add(new GraphRunner.Output("out", file, "download"));
        }
        return produced;
    }

    private Job awaitAnnounced() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (announced.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(!announced.isEmpty(), "nothing was ever announced");
        return announced.getFirst();
    }

    @Test
    void aWorkerClaimsAJobRunsItAndRecordsWhatItProduced() throws Exception {
        runProduces(1000, 2000);
        queue();

        start(NO_LIMIT);
        Job done = awaitAnnounced();

        assertEquals(JobState.DONE, done.state());
        assertEquals(2, done.results().size());
        assertEquals(3000, done.sizeBytes());
        assertEquals(JobState.DONE, store.find(done.id()).orElseThrow().state(),
                "and the row says so, which is the only account that survives a restart");
    }

    @Test
    void theUploadLimitIsPerFileAndNotPerJob() throws Exception {
        runProduces(3000, 3000);
        queue();

        start(4000);

        // Two pieces that each fit are deliverable however much they weigh together, because they
        // leave as two links.
        assertEquals(JobState.DONE, awaitAnnounced().state());
    }

    @Test
    void oneFileOverTheLimitIsTooBigRatherThanFailed() throws Exception {
        runProduces(5000);
        queue();

        start(1000);

        // Told apart from a failure on purpose: the file exists and the answer is "ask for less",
        // not "something went wrong".
        assertEquals(JobState.TOO_BIG, awaitAnnounced().state());
    }

    @Test
    void aJobThatFailedSaysWhyAndGivesItsDirectoryBack() throws Exception {
        when(runner.run(any(), any(), any()))
                .thenThrow(new java.io.IOException("the video is private"));
        queue();

        start(NO_LIMIT);
        Job failed = awaitAnnounced();

        assertEquals(JobState.FAILED, failed.state());
        assertTrue(failed.error().contains("private"), failed.error());
        assertEquals(0, media.bytesInUse(), "a failed job's half-written files are nobody's");
    }

    @Test
    void aFailureWithNothingToSayStillNamesItself() throws Exception {
        when(runner.run(any(), any(), any())).thenThrow(new IllegalStateException());
        queue();

        start(NO_LIMIT);

        // "IllegalStateException" is a poor answer and an empty one is worse: the chat prints it.
        assertTrue(awaitAnnounced().error() != null && !awaitAnnounced().error().isBlank());
    }

    @Test
    void theJobMovesToProcessingTheFirstTimeTheCpuTakesOver() throws Exception {
        List<JobState> seen = new ArrayList<>();
        when(runner.run(any(), any(), any())).thenAnswer(call -> {
            var progress = (su.grinev.mediabot.media.DownloadProgress) call.getArgument(2);
            progress.downloading(0.5);
            progress.processing(0);
            seen.add(store.find(1L).orElseThrow().state());
            progress.processing(0.5);
            seen.add(store.find(1L).orElseThrow().state());
            return produce(100);
        });
        queue();

        start(NO_LIMIT);
        awaitAnnounced();

        assertEquals(List.of(JobState.PROCESSING, JobState.PROCESSING), seen);
        // The download owns the first nine tenths of the bar and ffmpeg the last tenth.
        assertEquals(0.45, reported.get(0), 1e-9);
        assertEquals(0.9, reported.get(1), 1e-9);
        assertEquals(0.95, reported.get(2), 1e-9);
    }

    @Test
    void aJobCancelledBeforeItStartsIsNotRun() throws Exception {
        Job job = queue();
        store.markFailed(job.id(), JobState.CANCELLED, "cancelled");

        start(NO_LIMIT);
        Thread.sleep(200);

        org.mockito.Mockito.verify(runner, org.mockito.Mockito.never()).run(any(), any(), any());
        assertTrue(announced.isEmpty(), "nobody is told twice about a job they cancelled");
    }
}
