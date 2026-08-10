package su.grinev.mediabot.jobs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import su.grinev.mediabot.AgentProperties;
import su.grinev.mediabot.db.Database;
import su.grinev.mediabot.media.MediaStore;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * How much of the queue one chat may hold.
 *
 * <p>The count is of unfinished jobs, not of jobs ever asked for: the limit exists so one person
 * cannot fill a queue two workers are draining, and a job that has finished is not in anybody's way.
 */
class PerChatLimitTest {

    private static final int LIMIT = 8;
    private static final long CHAT = 1;

    private Database database;
    private JobStore store;
    private JobQueue queue;

    @BeforeEach
    void open(@TempDir Path directory) throws Exception {
        database = Database.inMemory();
        store = JobStore.on(database, 48);
        queue = new JobQueue(store, mock(JobWorkers.class), mock(MediaStore.class),
                properties(directory));
    }

    @AfterEach
    void close() throws Exception {
        database.close();
    }

    @Test
    void eightJobsFitAndTheNinthIsRefused() throws Exception {
        for (int i = 1; i <= LIMIT; i++) {
            queue.enqueue(JobSpec.download(CHAT, "https://youtu.be/v" + i, null));
        }

        var refused = assertThrows(JobQueue.Refused.class,
                () -> queue.enqueue(JobSpec.download(CHAT, "https://youtu.be/v9", null)));

        assertTrue(refused.getMessage().contains("8"), refused.getMessage());
        assertEquals(LIMIT, store.pendingIn(CHAT).size());
    }

    @Test
    void theLimitIsPerChatAndNotForEverybodyTogether() throws Exception {
        for (int i = 1; i <= LIMIT; i++) {
            queue.enqueue(JobSpec.download(CHAT, "https://youtu.be/v" + i, null));
        }

        assertDoesNotThrow(() -> queue.enqueue(JobSpec.download(2, "https://youtu.be/other", null)));
    }

    @Test
    void aFinishedJobStopsCountingAgainstTheChat() throws Exception {
        for (int i = 1; i <= LIMIT; i++) {
            queue.enqueue(JobSpec.download(CHAT, "https://youtu.be/v" + i, null));
        }
        store.markDone(store.pendingIn(CHAT).getFirst().id(), JobState.DONE,
                Path.of("work", "done.mp4"), 1024);

        assertDoesNotThrow(() -> queue.enqueue(JobSpec.download(CHAT, "https://youtu.be/v9", null)));
    }

    @Test
    void askingTwiceForTheSameThingIsNotTwoJobs() throws Exception {
        Job first = queue.enqueue(JobSpec.download(CHAT, "https://youtu.be/same", 720));
        Job again = queue.enqueue(JobSpec.download(CHAT, "https://youtu.be/same", 720));

        assertEquals(first.id(), again.id(), "a repeated ask must not spend one of the eight");
        assertEquals(1, store.pendingIn(CHAT).size());
    }

    private static AgentProperties properties(Path directory) {
        AgentProperties props = mock(AgentProperties.class);
        org.mockito.Mockito.when(props.jobs()).thenReturn(new AgentProperties.Jobs(
                directory.resolve("jobs.db"), 2, LIMIT, 48));
        return props;
    }
}
