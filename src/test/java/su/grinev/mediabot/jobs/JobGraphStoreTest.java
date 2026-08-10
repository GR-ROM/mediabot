package su.grinev.mediabot.jobs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import su.grinev.mediabot.db.Database;
import su.grinev.mediabot.graph.Graph;
import su.grinev.mediabot.graph.Node;
import su.grinev.mediabot.graph.PipelineText;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * What survives the round trip through the column, which is the only place the graph is not simply
 * the object it was built as.
 */
class JobGraphStoreTest {

    private static final String URL = "https://www.youtube.com/watch?v=jNQXAC9IVRw";

    private Database database;
    private JobStore store;

    @BeforeEach
    void open(@TempDir Path directory) throws Exception {
        database = Database.inMemory();
        store = JobStore.on(database, 48);
    }

    @AfterEach
    void close() throws Exception {
        database.close();
    }

    @Test
    void aChainComesBackOutOfTheColumnAsItWentIn() {
        Graph asked = PipelineText.read(
                "/download " + URL + " /cut 1:00-2:00 2:00-2:20 /encode mp4 480p /normalize");
        Job created = store.create(JobSpec.of(7, asked));

        Graph stored = store.find(created.id()).orElseThrow().graph();

        assertEquals(asked.nodes().size(), stored.nodes().size());
        assertEquals(2, stored.outputs().size());
        List<Node.Cut> cuts = stored.nodes().stream()
                .filter(Node.Cut.class::isInstance).map(Node.Cut.class::cast).toList();
        assertEquals(60, cuts.getFirst().window().startSeconds());
        assertEquals(140, cuts.get(1).window().endSeconds());
        assertEquals(480, stored.nodes().stream()
                .filter(Node.Encode.class::isInstance).map(Node.Encode.class::cast)
                .findFirst().orElseThrow().height());
    }

    @Test
    void aRowWithNoGraphIsStillARunnableJob() {
        Job created = store.create(JobSpec.download(7, URL, 720));
        database.call("blank the column", connection -> {
            try (var s = connection.createStatement()) {
                s.executeUpdate("update jobs set spec_json = null where id = " + created.id());
            }
            return null;
        });

        Graph rebuilt = store.find(created.id()).orElseThrow().graph();

        assertNotNull(rebuilt, "a job written before graphs existed still has work to do");
        assertEquals(URL, rebuilt.source().orElseThrow().url());
        assertEquals(1, rebuilt.outputs().size());
    }

    @Test
    void everyFileAJobProducedComesBackAndNotJustTheFirst() {
        Job created = store.create(JobSpec.of(7, PipelineText.read(
                "/download " + URL + " /cut 0:00-0:10 0:10-0:20")));
        // Claimed first, because that is what puts the job in the in-flight map — and the object in
        // that map, not the row, is the one handed to the chat to be published.
        Job claimed = store.claimNext().orElseThrow();
        List<Path> produced = List.of(Path.of("work", "piece 1.mp4"), Path.of("work", "piece 2.mp4"));

        store.markDone(created.id(), JobState.DONE, produced, 2048);

        assertEquals(produced, store.find(created.id()).orElseThrow().results());
        assertEquals(produced, claimed.results(),
                "the in-flight job is the one that gets published, so it needs the whole list too");
        assertEquals(produced.getFirst(), claimed.result(),
                "result_path keeps the first, so everything older still reads a job the same way");
    }
}
