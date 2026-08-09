package su.grinev.mediabot.jobs;

/**
 * Where a running job reports to.
 *
 * <p>An interface so the queue does not depend on Telegram — which is not tidiness but the thing
 * that keeps the dependency acyclic: the chat side has to depend on the queue to enqueue anything,
 * so the queue cannot also depend on it.
 */
public interface ProgressSink {

    void started(Job job);

    /** @param fraction 0..1 */
    void progress(Job job, double fraction);

    /** Called whatever the outcome, before the finished event is published. */
    void finished(Job job);

    /** For tests, and for a run with no chat attached. */
    ProgressSink NONE = new ProgressSink() {
        @Override
        public void started(Job job) {
            // nothing to report to
        }

        @Override
        public void progress(Job job, double fraction) {
            // nothing to report to
        }

        @Override
        public void finished(Job job) {
            // nothing to report to
        }
    };
}
