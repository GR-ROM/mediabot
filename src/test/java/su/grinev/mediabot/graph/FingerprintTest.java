package su.grinev.mediabot.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The canonical description a job is deduped on.
 *
 * <p>Written out literally here, and literally again in the Go port's {@code fingerprint_test.go},
 * because that is the only thing making the two agree: they hash this text, so identical text is
 * identical fingerprints and the same video is not downloaded twice by whichever of them is
 * running. Changing it is allowed; changing it in one of the two is the bug this test exists for.
 *
 * <p>Deliberately not the stored json. That format is free to gain a field or reorder one, and
 * every row already deduped would stop matching if the fingerprint were taken from it.
 */
class FingerprintTest {

    @Test
    void theFingerprintIsWrittenOutInFull() {
        Graph graph = PipelineText.read(
                "/download https://x.test/a /cut 1:00-2:00 0:10-0:20 /join");

        assertEquals("""
                fetch url=https://x.test/a h=-
                cut in=0 from=60 to=120
                cut in=0 from=10 to=20
                join in=1,2
                publish in=3""", Fingerprint.of(graph));
    }

    @Test
    void everyParameterOfEveryStepIsInIt() {
        Graph graph = PipelineText.read(
                "/download https://x.test/a /encode 720p mp4 high /normalize");

        assertEquals("""
                fetch url=https://x.test/a h=-
                encode in=0 c=mp4 q=HIGH h=720
                normalize in=1
                publish in=2""", Fingerprint.of(graph));
    }

    @Test
    void whatTheBuilderNamedTheStepsIsNotPartOfIt() {
        Graph named = PipelineText.read("/download https://x.test/a /cut 0:00-0:10");
        Graph renamed = new Graph(named.nodes().stream()
                .map(node -> switch (node) {
                    case Node.Fetch fetch -> (Node) new Node.Fetch("a", fetch.url(), fetch.maxHeight());
                    case Node.Cut cut -> (Node) new Node.Cut("b", "a", cut.window());
                    case Node.Publish publish -> (Node) new Node.Publish("c", "b");
                    default -> node;
                })
                .toList());

        assertEquals(Fingerprint.of(named), Fingerprint.of(renamed),
                "ids are minted by whoever assembled the graph; the work is the same either way");
    }

    @Test
    void aStepThatChangesTheFileChangesTheFingerprint() {
        assertNotEquals(
                Fingerprint.of(PipelineText.read("/download https://x.test/a /encode 720p")),
                Fingerprint.of(PipelineText.read("/download https://x.test/a /encode 1080p")));
    }

    @Test
    void howTheGraphIsStoredIsNotPartOfIt() {
        Graph graph = PipelineText.read("/download https://x.test/a /cut 0:00-0:10");

        assertEquals(Fingerprint.of(graph), Fingerprint.of(GraphJson.read(GraphJson.write(graph))),
                "a graph that went through the column and came back is the same work");
    }
}
