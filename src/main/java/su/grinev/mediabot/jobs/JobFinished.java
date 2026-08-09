package su.grinev.mediabot.jobs;

/**
 * Published when a job stops being pending, whatever the outcome.
 *
 * <p>A Spring event rather than a listener list injected into the queue, and that is a dependency
 * decision rather than a stylistic one: the chat side has to depend on the queue in order to enqueue
 * anything, so the queue must not depend on the chat side to report back.
 */
public record JobFinished(Job job) {}
