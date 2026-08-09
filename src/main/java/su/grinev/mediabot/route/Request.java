package su.grinev.mediabot.route;

import su.grinev.mediabot.jobs.JobSpec;

/**
 * What a message turned out to be asking for.
 *
 * <p>A closed set, which is the point: this bot does six things, and a message either is one of
 * them or is not understood. There is no seventh case reached by interpretation.
 */
public sealed interface Request {

    /** Fetch one video, in whichever of the three shapes was asked for. */
    record Fetch(JobSpec spec) implements Request {}

    /** Fetch the first {@code count} videos of a playlist. */
    record Playlist(String url, Integer maxHeight, int count) implements Request {}

    /** What is queued and what recently finished. */
    record Status() implements Request {}

    /** The link to a finished download: a given job, or the most recent one. */
    record Link(Long jobId) implements Request {}

    /** Stop a job, whether it has started or not. */
    record Cancel(long jobId) implements Request {}

    /** What this bot can be asked. */
    record Help() implements Request {}

    /** Let a chat in. Owners only, which is checked where it is answered, not here. */
    record Allow(long chatId, String note) implements Request {}

    /** Take a chat back out. */
    record Deny(long chatId) implements Request {}

    /** Who is on the list. */
    record Allowed() implements Request {}
}
