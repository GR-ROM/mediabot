package su.grinev.mediabot.access;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import su.grinev.mediabot.AgentProperties;
import su.grinev.mediabot.db.Database;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Who may use the bot, once that stopped being a line in a file.
 *
 * <p>The property worth testing hardest is the one that is not about permissions at all: an owner
 * has to work when the table is empty, missing or wrong, because the owner is the only person who
 * could put any of that right.
 */
class AccessListTest {

    private static final long OWNER = 111;
    private static final long FRIEND = 222;
    private static final long STRANGER = 333;

    private Database database;
    private Path file;

    @BeforeEach
    void open(@TempDir Path directory) throws Exception {
        file = directory.resolve("jobs.db");
        database = Database.at(file);
    }

    @AfterEach
    void close() throws Exception {
        database.close();
    }

    @Test
    void anOwnerWorksWithNothingInTheTable() {
        AccessList access = listWith(List.of(OWNER), List.of());

        assertTrue(access.isOwner(OWNER));
        assertTrue(access.permits(OWNER));
        assertFalse(access.permits(STRANGER), "an empty table now means nobody, not everybody");
    }

    @Test
    void somebodyLetInWorksWithoutARestart() {
        AccessList access = listWith(List.of(OWNER), List.of());

        assertFalse(access.permits(FRIEND));
        assertTrue(access.allow(FRIEND, OWNER, "from work"));
        assertTrue(access.permits(FRIEND), "the whole point of the table is that this needs no restart");

        assertFalse(access.allow(FRIEND, OWNER, "again"), "adding twice is not an error, but it is not news");
    }

    @Test
    void takingSomebodyBackOutTakesEffectAtOnce() {
        AccessList access = listWith(List.of(OWNER), List.of());
        access.allow(FRIEND, OWNER, null);

        assertTrue(access.deny(FRIEND));
        assertFalse(access.permits(FRIEND));
        assertFalse(access.deny(FRIEND), "denying somebody who was not there says so");
    }

    @Test
    void theConfiguredListIsMovedIntoTheTableOnce() {
        AccessList first = listWith(List.of(OWNER), List.of(FRIEND));
        assertTrue(first.permits(FRIEND));
        assertEquals(1, first.all().size());

        // Removed on purpose, and a restart must not undo that.
        first.deny(FRIEND);
        AccessList second = listWith(List.of(OWNER), List.of(FRIEND));

        assertFalse(second.permits(FRIEND), "seeding a non-empty table would re-add a removal");
        assertTrue(second.all().isEmpty());
    }

    @Test
    void whoAddedSomebodyAndWhyIsKept() {
        AccessList access = listWith(List.of(OWNER), List.of());
        access.allow(FRIEND, OWNER, "from work");

        AccessList.Entry entry = access.all().getFirst();
        assertEquals(FRIEND, entry.chatId());
        assertEquals(OWNER, entry.addedBy());
        assertEquals("from work", entry.note());
    }

    private AccessList listWith(List<Long> owners, List<Long> seed) {
        AgentProperties props = mock(AgentProperties.class);
        when(props.telegram()).thenReturn(new AgentProperties.Telegram(
                "t", "bot", "https://api.telegram.org", 1024, 0, owners, seed));
        when(props.media()).thenReturn(new AgentProperties.Media(
                Path.of("yt-dlp"), Path.of("ffmpeg"), Path.of("work"), List.of("youtube.com"),
                60, 1800, null, null, null, 720, 2160));
        AccessList access = new AccessList(database, props);
        access.createTable();
        return access;
    }
}
