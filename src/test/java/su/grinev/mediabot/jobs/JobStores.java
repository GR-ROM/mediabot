package su.grinev.mediabot.jobs;

import su.grinev.mediabot.db.Database;

/**
 * A job store on a given database, for a test outside this package.
 *
 * <p>The factory it calls is package-private because nothing in the running bot builds one by hand —
 * Spring does. That is worth keeping, and it is not worth a fake store to work around: what a test
 * of the chat side wants is the real claim-once update and the real state machine, on a database
 * that goes away with it.
 */
public final class JobStores {

    private JobStores() {
    }

    public static JobStore on(Database database, int retentionHours) {
        return JobStore.on(database, retentionHours);
    }
}
