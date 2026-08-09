package su.grinev.mediabot.jobs;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import su.grinev.mediabot.AgentProperties;
import su.grinev.mediabot.media.MediaStore;

import java.util.List;
import java.util.Optional;

/**
 * The queue itself: what is asked for, what is refused, and what a person is still owed.
 *
 * <p>This is the piece the whole design turns on. A tool that downloads inside its own call holds a
 * model round open for minutes, blocks the chat behind it, and loses the work entirely if the
 * process restarts. A tool that queues returns in milliseconds, and what happens afterwards is the
 * queue's business.
 *
 * <p>Queueing is now the whole of it: {@link #enqueue} writes a row and wakes a worker, and that is
 * all it does. Running the job belongs to {@link JobWorkers}, which claims rows from the same table.
 * The split is what makes the table the single account of what is happening — before it, a job was
 * half a row and half a task in an executor, and the two could disagree.
 */
@Component
@Slf4j
public class JobQueue {

    private final JobStore store;
    private final JobWorkers workers;
    private final MediaStore media;
    private final int perChatLimit;

    public JobQueue(JobStore store, JobWorkers workers, MediaStore media, AgentProperties props) {
        this.store = store;
        this.workers = workers;
        this.media = media;
        this.perChatLimit = props.jobs().perChatLimit();
    }

    /** A request the queue would not take, worded so the reason can go to the model. */
    public static class Refused extends Exception {
        public Refused(String message) {
            super(message);
        }
    }

    /**
     * Queues a download and returns at once.
     *
     * @param spec every parameter of the job, already complete
     * @return the job, either newly queued or the identical one already waiting
     * @throws Refused when this chat has too much queued already
     */
    public Job enqueue(JobSpec spec) throws Refused {

        Optional<Job> existing = store.findPendingLike(spec);
        if (existing.isPresent()) {
            log.info("chat {} asked again for something already queued as job {}",
                    spec.chatId(), existing.get().id());
            return existing.get();
        }
        int pending = store.pendingIn(spec.chatId()).size();
        if (pending >= perChatLimit) {
            throw new Refused(("this chat already has %d job(s) queued, which is the limit (%d). "
                    + "Wait for some to finish, or cancel the ones that are no longer wanted.")
                    .formatted(pending, perChatLimit));
        }
        Job job = store.create(spec);
        // The row is the job. Waking a worker only saves it the wait until its next look at the
        // table — a wake-up that never arrived would cost latency, not the download.
        workers.wake();
        log.info("queued job {} for chat {}: {} {} ({})", job.id(), spec.chatId(),
                spec.scenario(), spec.url(), spec.describe());
        return job;
    }

    /**
     * Stops a job, whether it has started or not.
     *
     * @return the job in its new state, or empty when there is no such job in this chat
     */
    public Optional<Job> cancel(long chatId, long jobId) {
        Optional<Job> found = store.find(jobId).filter(j -> j.chatId() == chatId);
        if (found.isEmpty() || found.get().state().isFinished()) {
            return found;
        }
        Job job = found.get();
        Job cancelled = store.markFailed(jobId, JobState.CANCELLED, "cancelled");
        job.interrupt();
        media.release(jobId);
        return Optional.of(cancelled);
    }

    public List<Job> pendingIn(long chatId) {
        return store.pendingIn(chatId);
    }

    public List<Job> recentIn(long chatId, int limit) {
        return store.recentIn(chatId, limit);
    }

    public Optional<Job> find(long jobId) {
        return store.find(jobId);
    }

    /**
     * Records where a finished file went after delivery moved it.
     *
     * <p>Otherwise the row keeps pointing at the per-job working directory the file was published
     * out of, and every later reading of it — a second link, a status answer — is a path to nothing.
     */
    public Job updateResult(long jobId, java.nio.file.Path file, long sizeBytes) {
        return store.markDone(jobId, JobState.DONE, file, sizeBytes);
    }

    /**
     * The link is gone and so is the file: the job is over in a way that can be told from having
     * failed, so a person asking about it is told to download it again rather than to wait.
     */
    public Job expire(long jobId) {
        return store.markState(jobId, JobState.EXPIRED);
    }

    /**
     * Announces what a restart destroyed.
     *
     * <p>Called by whoever can actually talk to a chat, not from here: at the moment the store is
     * built there may be no bot yet, and an event published into an empty room is a person left
     * waiting.
     *
     * <p>Must run before the workers start claiming, or one of them picks up last run's abandoned
     * row and downloads it behind the message saying it was abandoned. {@link JobWorkers} starts on
     * {@code ApplicationReadyEvent} and this is called from an {@code ApplicationRunner}, which
     * Spring runs first.
     */
    public List<Job> takeInterrupted() {
        return store.markInterrupted();
    }

    public int sweep() {
        return store.sweep();
    }
}
