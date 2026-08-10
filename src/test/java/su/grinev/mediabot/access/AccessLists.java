package su.grinev.mediabot.access;

import su.grinev.mediabot.AgentProperties;
import su.grinev.mediabot.db.Database;

/**
 * An access list that is open, for a test outside this package.
 *
 * <p>Only exists because the table is created by a lifecycle method Spring calls and nothing else
 * may. Kept to that one line rather than becoming a fake: what a test of the chat side needs is the
 * real allow and deny, on a database that goes away with it.
 */
public final class AccessLists {

    private AccessLists() {
    }

    public static AccessList opened(Database database, AgentProperties props) {
        AccessList list = new AccessList(database, props);
        list.createTable();
        return list;
    }
}
