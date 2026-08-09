package su.grinev.mediabot.jobs;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import su.grinev.mediabot.AgentProperties;
import su.grinev.mediabot.graph.GraphRunner;
import su.grinev.mediabot.media.DownloadProgress;
import su.grinev.mediabot.media.MediaStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

@Component
@Slf4j
public class JobWorkers {
    private static final long IDLE_PARK_SECONDS = 30;

    /** How much of the progress bar the download owns; the rest belongs to ffmpeg. */
    private static final double DOWNLOAD_SHARE = 0.9;

    private final JobStore store;
    private final GraphRunner runner;
    private final MediaStore media;
    private final ApplicationEventPublisher events;
    private final ObjectProvider<ProgressSink> sinks;
    private final int concurrency;
    private final long maxUploadBytes;
    private final List<Thread> workers = new CopyOnWriteArrayList<>();
    private volatile boolean stopping;

    public JobWorkers(JobStore store, GraphRunner runner,
                      MediaStore media, ApplicationEventPublisher events,
                      ObjectProvider<ProgressSink> sinks, AgentProperties props) {
        this.store = store;
        this.runner = runner;
        this.media = media;
        this.events = events;
        this.sinks = sinks;
        this.concurrency = props.jobs().concurrency();
        this.maxUploadBytes = props.telegram().maxUploadBytes();
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (!workers.isEmpty()) {
            return;
        }
        for (int i = 1; i <= concurrency; i++) {
            workers.add(Thread.ofPlatform().name("job-" + i).daemon(false).unstarted(this::loop));
        }
        workers.forEach(Thread::start);
        log.info("{} job worker(s) claiming from the queue", concurrency);
    }

    public void wake() {
        workers.forEach(LockSupport::unpark);
    }

    private void loop() {
        while (!stopping) {
            Thread.interrupted();

            boolean ranSomething = false;
            try {
                ranSomething = claimAndRun();
            } catch (RuntimeException e) {
                log.error("worker recovered from an unexpected failure", e);
            }

            if (!ranSomething && !stopping) {
                LockSupport.parkNanos(this, TimeUnit.SECONDS.toNanos(IDLE_PARK_SECONDS));
            }
        }
    }

    private boolean claimAndRun() {
        Optional<Job> claimed = store.claimNext();

        if (claimed.isEmpty()) {
            return false;
        }

        Job job = claimed.get();
        job.claimedBy(Thread.currentThread());
        try {
            if (store.find(job.id())
                    .map(Job::state)
                    .orElse(JobState.CANCELLED) != JobState.DOWNLOADING) {
                log.info("job {} was cancelled before it started", job.id());
                return true;
            }
            process(job);
            return true;
        } finally {
            job.released();
        }
    }

    private void process(Job job) {
        long jobId = job.id();
        ProgressSink sink = sinks.getIfAvailable(() -> ProgressSink.NONE);
        sink.started(job);

        Path directory = null;
        try {
            directory = media.directoryFor(jobId);
            List<GraphRunner.Output> produced = runner.run(job.graph(), directory,
                    phases(job, sink));
            List<Path> files = produced.stream().map(GraphRunner.Output::file).toList();

            long size = 0;
            for (Path file : files) {
                size += Files.size(file);
            }
            // The limit is per file, not per job: two pieces that each fit are deliverable however
            // much they weigh together, because they leave as two links.
            long largest = 0;
            for (Path file : files) {
                largest = Math.max(largest, Files.size(file));
            }
            JobState state = largest > maxUploadBytes ? JobState.TOO_BIG : JobState.DONE;
            Job done = store.markDone(jobId, state, files, size);
            log.info("job {} finished as {} ({} file(s), {} bytes)", jobId, state, files.size(), size);
            report(sink, done);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("job {} stopped while running", jobId);
            releaseQuietly(jobId, directory);

        } catch (Exception e) {
            String reason = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("job {} failed: {}", jobId, reason);
            Job failed = store.markFailed(jobId, JobState.FAILED, reason);
            releaseQuietly(jobId, directory);
            report(sink, failed);
        }
    }

    /**
     * The one place a job moves from DOWNLOADING to PROCESSING.
     *
     * <p>Driven by the work rather than announced by it: whichever step first reports processing —
     * the merge, the audio extraction, the re-encode — is the moment the bytes are all here and the
     * CPU has taken over, and that is exactly what the state is meant to say. Written once, because
     * the update is a row and a progress callback arrives many times a second.
     */
    private DownloadProgress phases(Job job, ProgressSink sink) {
        var announced = new AtomicBoolean();
        return DownloadProgress.of(
                fraction -> sink.progress(job, fraction * DOWNLOAD_SHARE),
                fraction -> {
                    if (announced.compareAndSet(false, true)) {
                        store.markState(job.id(), JobState.PROCESSING);
                        log.debug("job {} is processing", job.id());
                    }
                    sink.progress(job, DOWNLOAD_SHARE + fraction * (1 - DOWNLOAD_SHARE));
                });
    }

    private void report(ProgressSink sink, Job job) {
        try {
            sink.finished(job);
        } catch (RuntimeException e) {
            log.warn("progress sink failed for job {}: {}", job.id(), e.toString());
        }
        events.publishEvent(new JobFinished(job));
    }

    private void releaseQuietly(long jobId, Path directory) {
        if (directory != null) {
            media.release(jobId);
        }
    }

    @PreDestroy
    void shutdown() {
        stopping = true;
        workers.forEach(LockSupport::unpark);
        workers.forEach(Thread::interrupt);
        for (Thread worker : workers) {
            try {
                worker.join(TimeUnit.SECONDS.toMillis(10));
                if (worker.isAlive()) {
                    log.warn("{} did not stop in time", worker.getName());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
