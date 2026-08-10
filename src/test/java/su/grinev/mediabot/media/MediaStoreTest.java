package su.grinev.mediabot.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import su.grinev.mediabot.Fixtures;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A directory per job, and the promise that it goes away afterwards.
 *
 * <p>Not interesting until it fails, which is why it is worth a test: a bot that leaks one directory
 * per failed download fills a disk over weeks, and the first symptom is every download failing at
 * once.
 */
class MediaStoreTest {

    private MediaStore opened(Path root) throws IOException {
        MediaStore store = new MediaStore(Fixtures.builder().workDir(root).build());
        store.prepare();
        return store;
    }

    @Test
    void aStartupClearsWhatTheLastRunLeftBehind(@TempDir Path temp) throws IOException {
        Path root = temp.resolve("work");
        Files.createDirectories(root.resolve("job-1"));
        Files.write(root.resolve("job-1").resolve("half.mp4.part"), new byte[1024]);
        Files.createDirectories(root.resolve("job-2").resolve("nested"));
        Files.write(root.resolve("job-2").resolve("nested").resolve("deep.mp4"), new byte[1024]);

        MediaStore store = opened(root);

        // Safe at startup and only at startup: no job survives the process, so there is nothing
        // here anybody is still waiting for.
        assertEquals(0, store.bytesInUse());
        try (var left = Files.list(root)) {
            assertEquals(0, left.count());
        }
    }

    @Test
    void eachJobGetsItsOwnDirectory(@TempDir Path temp) throws IOException {
        MediaStore store = opened(temp.resolve("work"));

        Path one = store.directoryFor(1);
        Path other = store.directoryFor(2);

        assertNotEquals(one, other, "two jobs writing into one directory publish the wrong file");
        assertTrue(Files.isDirectory(one));
        assertTrue(Files.isDirectory(other));
    }

    @Test
    void askingAgainForTheSameJobStartsItClean(@TempDir Path temp) throws IOException {
        MediaStore store = opened(temp.resolve("work"));
        Path directory = store.directoryFor(1);
        Files.write(directory.resolve("from-the-last-attempt.mp4"), new byte[512]);

        Path again = store.directoryFor(1);

        // A retry that finds the previous attempt's file still there publishes it as the new one.
        try (var left = Files.list(again)) {
            assertEquals(0, left.count());
        }
    }

    @Test
    void releasingAJobTakesEverythingItWrote(@TempDir Path temp) throws IOException {
        MediaStore store = opened(temp.resolve("work"));
        Path directory = store.directoryFor(1);
        Files.createDirectories(directory.resolve("nested"));
        Files.write(directory.resolve("nested").resolve("video.mp4"), new byte[4096]);

        store.release(1);

        assertFalse(Files.exists(directory));
        assertEquals(0, store.bytesInUse());
    }

    @Test
    void releasingSomethingThatWasNeverThereIsNotAFailure(@TempDir Path temp) throws IOException {
        MediaStore store = opened(temp.resolve("work"));

        store.release(999);   // a job that failed before it got a directory
    }

    @Test
    void whatIsOnDiskCanBeCounted(@TempDir Path temp) throws IOException {
        MediaStore store = opened(temp.resolve("work"));
        Files.write(store.directoryFor(1).resolve("a.mp4"), new byte[1000]);
        Path nested = store.directoryFor(2).resolve("nested");
        Files.createDirectories(nested);
        Files.write(nested.resolve("b.mp4"), new byte[2000]);

        assertEquals(3000, store.bytesInUse());
    }
}
