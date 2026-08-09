package su.grinev.mediabot.media;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import su.grinev.mediabot.AgentProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * A directory per job, and the promise that it goes away afterwards.
 *
 * <p>Written as a class rather than left to each caller because the failure it prevents is silent:
 * a bot that leaks one directory per failed download fills a disk over weeks, and the first symptom
 * is every download failing at once.
 */
@Component
@Slf4j
public class MediaStore {

    private final Path root;

    public MediaStore(AgentProperties props) {
        this.root = props.media().workDir();
    }

    /**
     * Clears anything a previous run left behind.
     *
     * <p>Safe to do at startup and only at startup: a directory under this root can only belong to a
     * job, and no job survives the process — the queue marks them interrupted rather than resuming
     * them, so there is nothing here anybody is still waiting for.
     */
    @PostConstruct
    void prepare() throws IOException {
        Files.createDirectories(root);
        try (Stream<Path> leftovers = Files.list(root)) {
            leftovers.forEach(MediaStore::deleteTree);
        }
    }

    /** A fresh directory for one job to work in. */
    public Path directoryFor(long jobId) throws IOException {
        Path dir = root.resolve("job-" + jobId);
        deleteTree(dir);
        Files.createDirectories(dir);
        return dir;
    }

    /** Throws away a job's directory and everything in it. */
    public void release(long jobId) {
        deleteTree(root.resolve("job-" + jobId));
    }

    /** How much space the downloads are currently taking, for reporting it. */
    public long bytesInUse() {
        try (Stream<Path> tree = Files.walk(root)) {
            return tree.filter(Files::isRegularFile).mapToLong(file -> {
                try {
                    return Files.size(file);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    private static void deleteTree(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> tree = Files.walk(path)) {
            // Deepest first: a directory cannot be removed while anything is still in it.
            tree.sorted(Comparator.reverseOrder()).forEach(entry -> {
                try {
                    Files.deleteIfExists(entry);
                } catch (IOException e) {
                    log.debug("could not delete {}: {}", entry, e.toString());
                }
            });
        } catch (IOException e) {
            log.warn("could not clean up {}: {}", path, e.toString());
        }
    }
}
