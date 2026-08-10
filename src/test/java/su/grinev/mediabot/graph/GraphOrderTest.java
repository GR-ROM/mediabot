package su.grinev.mediabot.graph;

import org.junit.jupiter.api.Test;
import su.grinev.mediabot.media.Trim;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The order of the steps, which belongs to whoever wrote them.
 *
 * <p>{@code /audio /cut} takes the sound and cuts that; {@code /cut /audio} cuts the video and takes
 * the sound of each piece. Those are different jobs, so the list is not something to normalise — it
 * is the request. A graph whose steps do not run in the order they are written is one somebody wrote
 * down wrong, and it is refused rather than rearranged.
 *
 * <p>Which is what makes the loop check free: if every node must stand after its own inputs, a loop
 * cannot be expressed at all.
 */
class GraphOrderTest {

    private static Graph of(Node... nodes) {
        return new Graph(List.of(nodes));
    }

    private static List<String> idsOf(Graph graph) {
        return graph.inOrder().stream().map(Node::id).toList();
    }

    private static String refusalFrom(Node... nodes) {
        return assertThrows(IllegalArgumentException.class, () -> of(nodes)).getMessage();
    }

    @Test
    void theStepsAreHeldInTheOrderTheyWereWrittenIn() {
        Graph graph = of(
                new Node.Fetch("src", "https://x.test/a", null),
                new Node.Cut("cut", "src", new Trim(0, 10)),
                new Node.Publish("out", "cut"));

        assertEquals(List.of("src", "cut", "out"), idsOf(graph));
        assertEquals(idsOf(graph), graph.nodes().stream().map(Node::id).toList(),
                "one list, not a view of it computed on every read");
    }

    @Test
    void nodesHandedOverBackwardsAreRefusedRatherThanRearranged() {
        String refusal = refusalFrom(
                new Node.Publish("out", "cut"),
                new Node.Cut("cut", "src", new Trim(0, 10)),
                new Node.Fetch("src", "https://x.test/a", null));

        assertTrue(refusal.contains("out of order"), refusal);
        assertTrue(refusal.contains("cut"), "and it names what could not be resolved: " + refusal);
    }

    @Test
    void anInputThatIsNotThereIsToldApartFromOneThatComesLater() {
        String missing = refusalFrom(
                new Node.Fetch("src", "https://x.test/a", null),
                new Node.Cut("cut", "nowhere", new Trim(0, 10)),
                new Node.Publish("out", "cut"));

        // Two different mistakes with two different fixes: one is a step nobody wrote, the other is
        // a step written in the wrong place.
        assertTrue(missing.contains("not here"), missing);
        assertTrue(!missing.contains("out of order"), missing);
    }

    @Test
    void stepsThatLoopBackCannotBeExpressedAtAll() {
        String refusal = refusalFrom(
                new Node.Fetch("src", "https://x.test/a", null),
                new Node.Cut("a", "b", new Trim(0, 10)),
                new Node.Cut("b", "a", new Trim(0, 10)),
                new Node.Publish("out", "a"));

        assertTrue(refusal.contains("loop"), refusal);
    }

    @Test
    void aForkKeepsEveryBranchBehindWhatItGrewFrom() {
        Graph graph = of(
                new Node.Fetch("src", "https://x.test/a", null),
                new Node.Cut("cut1", "src", new Trim(0, 10)),
                new Node.Cut("cut2", "src", new Trim(20, 30)),
                new Node.Publish("out1", "cut1"),
                new Node.Publish("out2", "cut2"));

        List<String> order = idsOf(graph);

        assertEquals("src", order.getFirst());
        assertTrue(order.indexOf("cut1") < order.indexOf("out1"), order.toString());
        assertTrue(order.indexOf("cut2") < order.indexOf("out2"), order.toString());
    }

    @Test
    void aJoinMayNotBeWrittenBeforeThePiecesItJoins() {
        String refusal = refusalFrom(
                new Node.Fetch("src", "https://x.test/a", null),
                new Node.Concat("join", List.of("cut1", "cut2")),
                new Node.Cut("cut1", "src", new Trim(0, 10)),
                new Node.Cut("cut2", "src", new Trim(20, 30)),
                new Node.Publish("out", "join"));

        assertTrue(refusal.contains("out of order"), refusal);
    }

    @Test
    void aJoinWrittenAfterThemIsFine() {
        Graph graph = of(
                new Node.Fetch("src", "https://x.test/a", null),
                new Node.Cut("cut1", "src", new Trim(0, 10)),
                new Node.Cut("cut2", "src", new Trim(20, 30)),
                new Node.Concat("join", List.of("cut1", "cut2")),
                new Node.Publish("out", "join"));

        assertEquals(List.of("src", "cut1", "cut2", "join", "out"), idsOf(graph));
    }

    @Test
    void everythingTheBuilderProducesIsAlreadyInOrder() {
        // Which is why this check costs nothing on the ordinary path: the slash reader, the model
        // and every rewrite emit the steps in the order they run.
        for (String written : List.of(
                "/download https://x.test/a",
                "/download https://x.test/a /cut 1:00-2:00 2:00-2:20 /encode 480p /normalize",
                "/download https://x.test/a /cut 1:00-2:00 2:00-2:20 /join",
                "/download https://x.test/a /audio mp3")) {

            Graph graph = PipelineText.read(written);
            assertEquals(graph.nodes(), graph.inOrder(), written);
        }
    }

    @Test
    void aGraphThatWentThroughTheColumnComesBackInTheSameOrder() {
        Graph built = PipelineText.read(
                "/download https://x.test/a /cut 1:00-2:00 2:00-2:20 /encode 480p");

        Graph back = GraphJson.read(GraphJson.write(built));

        assertEquals(idsOf(built), idsOf(back),
                "the stored document is written in order, so reading it never has to fix one");
    }
}
