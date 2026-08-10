package su.grinev.mediabot.telegram;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import su.grinev.mediabot.Fixtures;
import su.grinev.mediabot.access.AccessList;
import su.grinev.mediabot.access.AccessLists;
import su.grinev.mediabot.db.Database;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The commands only the owner may run, and what the bot says when somebody else tries.
 *
 * <p>Run against the real access list on a database that lives in this process. A mocked one would
 * have made every assertion here a restatement of its own stubbing — "when allow returns true, it
 * says it worked" — where what is worth knowing is that allowing somebody actually lets them in.
 *
 * <p>A stranger never reaches here at all: the dispatcher drops them without a word, so the bot
 * gives away nothing to somebody probing it. Being told "that is not yours to do" is for the people
 * who are legitimately here and simply are not the owner.
 */
class AdminRepliesTest {

    private static final long OWNER = 500000001L;
    private static final long GUEST = 600000002L;
    private static final long NEWCOMER = 123456789L;

    private Database database;
    private AccessList access;
    private InMemoryBot bot;
    private AdminReplies admin;

    @BeforeEach
    void setUp() throws Exception {
        database = Database.inMemory();
        access = AccessLists.opened(database, Fixtures.builder().owners(OWNER).build());
        bot = new InMemoryBot();
        admin = new AdminReplies(bot, access);
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    @Test
    void onlyAnOwnerMayLetSomebodyIn() {
        admin.allow(GUEST, NEWCOMER, null);

        assertTrue(bot.said().contains("not yours to do"), bot.said());
        assertFalse(access.permits(NEWCOMER), "and it did not happen");
    }

    @Test
    void anOwnerLetsSomebodyInWithANote() {
        admin.allow(OWNER, NEWCOMER, "works with me");

        assertTrue(access.permits(NEWCOMER), "which is the whole point of the command");
        assertTrue(bot.said().contains(String.valueOf(NEWCOMER)), bot.said());
        assertTrue(bot.said().contains("works with me"), bot.said());
    }

    @Test
    void allowingSomebodyWhoCouldAlreadyUseItSaysSo() {
        admin.allow(OWNER, NEWCOMER, null);

        admin.allow(OWNER, NEWCOMER, null);

        assertTrue(bot.said().contains("could already use the bot"), bot.said());
    }

    @Test
    void anOwnerIsNotSomethingToAllow() {
        admin.allow(OWNER, OWNER, null);

        assertTrue(bot.said().contains("owner already"), bot.said());
    }

    @Test
    void anOwnerCannotBeDeniedFromTheChat() {
        admin.deny(OWNER, OWNER);

        // Owners come from the configuration, so removing one here would report a success that did
        // not happen and leave the person still able to do everything.
        assertTrue(bot.said().contains("configuration"), bot.said());
        assertTrue(access.permits(OWNER), "and they can still use their own bot");
    }

    @Test
    void denyingTakesSomebodyBackOut() {
        admin.allow(OWNER, NEWCOMER, null);

        admin.deny(OWNER, NEWCOMER);

        assertFalse(access.permits(NEWCOMER));
        assertTrue(bot.said().contains("cannot use the bot any more"), bot.said());
    }

    @Test
    void denyingSomebodyWhoWasNeverThereSaysSo() {
        admin.deny(OWNER, NEWCOMER);

        assertTrue(bot.said().contains("was not on the list"), bot.said());
    }

    @Test
    void theListShowsOwnersAndEverybodyElse() {
        admin.allow(OWNER, NEWCOMER, "works with me");

        admin.allowed(OWNER);

        assertTrue(bot.said().contains(String.valueOf(OWNER)), bot.said());
        assertTrue(bot.said().contains("Allowed (1)"), bot.said());
        assertTrue(bot.said().contains("works with me"), bot.said());
    }

    @Test
    void anEmptyListSaysSoRatherThanShowingAHeadingWithNothingUnderIt() {
        admin.allowed(OWNER);

        assertTrue(bot.said().contains("Nobody else has been let in"), bot.said());
    }

    @Test
    void aGuestSeesNoneOfTheList() {
        admin.allow(OWNER, NEWCOMER, "a name a guest has no business reading");

        admin.allowed(GUEST);

        assertTrue(bot.said().contains("not yours to do"), bot.said());
        assertFalse(bot.said().contains("no business reading"), bot.said());
    }
}
