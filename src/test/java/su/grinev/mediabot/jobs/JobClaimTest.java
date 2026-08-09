package su.grinev.mediabot.jobs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import su.grinev.mediabot.db.Database;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The property the whole worker design rests on: a job is claimed by exactly one worker.
 *
 * <p>Worth a test of its own because it is the guarantee that used to be free and no longer is. When
 * enqueueing submitted the task itself, one call meant one runner by construction. Now the table is
 * the queue and any number of threads may reach for the same row, so "exactly one" is a claim about
 * this code rather than about the shape of the program — and the failure it prevents is a video
 * downloaded twice and sent twice, which nobody would read as a locking bug.
 */
class JobClaimTest {

    private Database database;
    private JobStore store;

    @BeforeEach
    void open(@TempDir Path directory) throws Exception {
        database = Database.at(directory.resolve("jobs.db"));
        store = JobStore.on(database, 48);
    }

    @AfterEach
    void close() throws Exception {
        database.close();
    }

    @Test
    void aNewJobWaitsUntilItIsClaimed() {
        Job job = queue("https://example.test/a");

        assertEquals(JobState.PENDING, job.state(), "queueing must not start anything");

        Job claimed = store.claimNext().orElseThrow();

        assertEquals(job.id(), claimed.id());
        assertEquals(JobState.DOWNLOADING, claimed.state(),
                "the claim is the state change, not something the worker does afterwards");
        assertTrue(store.claimNext().isEmpty(), "and the job is gone from the queue once claimed");
    }

    @Test
    void theOldestJobGoesFirst() {
        long first = queue("https://example.test/first").id();
        long second = queue("https://example.test/second").id();

        assertEquals(first, store.claimNext().orElseThrow().id());
        assertEquals(second, store.claimNext().orElseThrow().id());
    }

    @Test
    void aClaimedJobIsHandedBackAsTheSameObject() {
        Job queued = queue("https://example.test/live");
        Job claimed = store.claimNext().orElseThrow();

        assertSame(claimed, store.find(queued.id()).orElseThrow(),
                "the thread on a Job is only reachable if a job in flight has one instance");
    }

    @Test
    void interruptingThroughTheJobReachesTheThreadRunningIt() throws Exception {
        long id = queue("https://example.test/slow").id();
        CountDownLatch working = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();

        Thread worker = Thread.ofPlatform().start(() -> {
            Job job = store.claimNext().orElseThrow();
            job.claimedBy(Thread.currentThread());
            working.countDown();
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(30));
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });

        assertTrue(working.await(10, TimeUnit.SECONDS), "the worker never got going");
        assertTrue(store.find(id).orElseThrow().interrupt(), "nothing was there to interrupt");
        worker.join(TimeUnit.SECONDS.toMillis(10));

        assertTrue(interrupted.get(), "the download must be stopped, not merely marked cancelled");
    }

    @Test
    void aJobNobodyIsRunningHasNothingToInterrupt() {
        Job queued = queue("https://example.test/waiting");

        assertFalse(queued.interrupt(), "a job still waiting is cancelled by its row, not a thread");
    }

    @Test
    void aCancelledJobIsNeverClaimed() {
        Job job = queue("https://example.test/unwanted");
        store.markFailed(job.id(), JobState.CANCELLED, "cancelled");

        assertTrue(store.claimNext().isEmpty(),
                "a job cancelled before a worker got to it must not be picked up at all");
    }

    @Test
    void twoWorkersReachingAtOnceNeverGetTheSameJob() throws Exception {
        int jobs = 40;
        int workers = 8;
        for (int i = 0; i < jobs; i++) {
            queue("https://example.test/" + i);
        }

        // Started together on purpose: claims that never overlap prove nothing about a claim that is
        // meant to be atomic.
        CountDownLatch go = new CountDownLatch(1);
        Set<Long> claimed = ConcurrentHashMap.newKeySet();
        List<Long> duplicates = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < workers; i++) {
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (Optional<Job> job = store.claimNext(); job.isPresent(); job = store.claimNext()) {
                    if (!claimed.add(job.get().id())) {
                        synchronized (duplicates) {
                            duplicates.add(job.get().id());
                        }
                    }
                }
            }));
        }
        go.countDown();
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(30));
        }

        assertTrue(duplicates.isEmpty(), "handed to more than one worker: " + duplicates);
        assertEquals(jobs, claimed.size(), "every job has to be claimed by somebody");
        assertFalse(store.pendingIn(1).stream().anyMatch(j -> j.state() == JobState.PENDING),
                "and nothing may be left waiting");
    }

    private Job queue(String url) {
        return store.create(JobSpec.download(1, url, null));
    }
}
