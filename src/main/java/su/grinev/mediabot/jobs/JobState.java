package su.grinev.mediabot.jobs;

/**
 * Where a job has got to.
 *
 * <p>The ways of not succeeding are separate states rather than one FAILED with a message, because
 * each has a different answer for the person waiting: a failure needs explaining, a file that came
 * back too big can still be re-encoded into one that fits, an interrupted job needs only a
 * "again?", and an expired one needs downloading afresh — and the code that routes them should
 * switch on a value, not read a string.
 *
 * <p>The happy path is PENDING → DOWNLOADING → PROCESSING → DONE → EXPIRED. Downloading and
 * processing are told apart because they fail differently and are paced differently: the first is
 * network and can be resumed in principle, the second is this machine's CPU and cannot. A person
 * watching a progress bar that has sat at 100% for two minutes is watching ffmpeg, and saying so is
 * the difference between "it is working" and "it is stuck".
 */
public enum JobState {

    /** Written to the table and waiting for a worker to claim it. Nothing is running yet. */
    PENDING,

    /** Claimed by exactly one worker thread, which is pulling bytes now. */
    DOWNLOADING,

    /** The bytes are here; ffmpeg is merging, extracting the audio, or re-encoding. */
    PROCESSING,

    /** Finished, file published, link live. */
    DONE,

    /** The link's time was up: the file has been deleted and the link answers nothing. */
    EXPIRED,

    /** Finished, but the file is over what this bot will hand over. */
    TOO_BIG,

    /** The download itself failed; {@code error} says why, in a person's words. */
    FAILED,

    /** The process died while this was running. Nothing is retrying it. */
    INTERRUPTED,

    CANCELLED;

    /** Claimed by a worker, whichever half of the work it is in. */
    public boolean isRunning() {
        return this == DOWNLOADING || this == PROCESSING;
    }

    public boolean isFinished() {
        return this != PENDING && !isRunning();
    }

    /** Whether the person waiting still has reason to expect something. */
    public boolean isPending() {
        return this == PENDING || isRunning();
    }
}
