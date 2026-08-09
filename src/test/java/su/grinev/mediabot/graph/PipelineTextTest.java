package su.grinev.mediabot.graph;

import org.junit.jupiter.api.Test;
import su.grinev.mediabot.media.AudioFormat;
import su.grinev.mediabot.media.Container;
import su.grinev.mediabot.media.Quality;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The slash form is strict on purpose, so most of what is worth testing is what it refuses.
 */
class PipelineTextTest {

    private static final String URL = "https://www.youtube.com/watch?v=jNQXAC9IVRw";

    @Test
    void oneStepIsStillAGraph() {
        Graph graph = PipelineText.read("/download " + URL);

        assertEquals(URL, graph.source().orElseThrow().url());
        assertEquals(1, graph.outputs().size(), "a download nobody publishes produces nothing");
        assertEquals(2, graph.nodes().size());
    }

    @Test
    void twoCutsMakeTwoBranchesAndTwoLinks() {
        Graph graph = PipelineText.read("/download " + URL + " /cut 1:00-2:00 2:00-2:20");

        assertEquals(2, cuts(graph).size());
        assertEquals(2, graph.outputs().size());
        assertEquals(60, cuts(graph).getFirst().window().startSeconds());
        assertEquals(120, cuts(graph).getFirst().window().endSeconds());
        assertEquals(140, cuts(graph).get(1).window().endSeconds());
    }

    @Test
    void aStepAfterAForkAppliesToEveryBranch() {
        Graph graph = PipelineText.read(
                "/download " + URL + " /cut 1:00-2:00 2:00-2:20 /encode mp4 720p /normalize");

        assertEquals(2, cuts(graph).size());
        assertEquals(2, nodes(graph, Node.Encode.class).size());
        assertEquals(2, nodes(graph, Node.Normalize.class).size());
        assertEquals(2, graph.outputs().size());
        nodes(graph, Node.Encode.class).forEach(encode -> {
            assertEquals(Container.MP4, encode.container());
            assertEquals(720, encode.height());
        });
    }

    @Test
    void everyNodeComesAfterWhatItTakesItsInputFrom() {
        Graph graph = PipelineText.read(
                "/download " + URL + " /cut 0:00-0:10 0:10-0:20 /encode 480p /join");

        List<String> seen = new java.util.ArrayList<>();
        for (Node node : graph.inOrder()) {
            node.inputs().forEach(input ->
                    assertTrue(seen.contains(input), node.id() + " came before its input " + input));
            seen.add(node.id());
        }
        assertEquals(1, graph.outputs().size(), "joined back into one file, so one link");
    }

    @Test
    void joiningNeedsSomethingToJoin() {
        var refused = assertThrows(Pipeline.Invalid.class,
                () -> PipelineText.read("/download " + URL + " /join"));

        assertTrue(refused.getMessage().contains("nothing to join"), refused.getMessage());
    }

    @Test
    void anUnknownWordStopsTheWholeThingAndIsQuotedBack() {
        var refused = assertThrows(Pipeline.Invalid.class,
                () -> PipelineText.read("/download " + URL + " /encode mp4 720p sharpen"));

        assertTrue(refused.getMessage().contains("sharpen"), refused.getMessage());
    }

    @Test
    void anUnknownStepIsNamedRatherThanIgnored() {
        var refused = assertThrows(Pipeline.Invalid.class,
                () -> PipelineText.read("/download " + URL + " /deblur"));

        assertTrue(refused.getMessage().contains("/deblur"), refused.getMessage());
    }

    @Test
    void aRangeWithNoEndIsAnErrorAndNotAGuess() {
        var refused = assertThrows(Pipeline.Invalid.class,
                () -> PipelineText.read("/download " + URL + " /cut 1:00"));

        assertTrue(refused.getMessage().contains("1:00"), refused.getMessage());
    }

    @Test
    void theDownloadHasToComeFirst() {
        assertThrows(Pipeline.Invalid.class, () -> PipelineText.read("/cut 1:00-2:00"));
        assertThrows(Pipeline.Invalid.class,
                () -> PipelineText.read("/download " + URL + " /download " + URL));
    }

    @Test
    void aChainIsToldApartFromAPlainCommand() {
        assertTrue(PipelineText.isChain("/download " + URL + " /cut 1:00-2:00"));
        assertFalse(PipelineText.isChain("/download " + URL),
                "one segment is the plain command the router already reads");
        assertFalse(PipelineText.isChain("download it please"));
    }

    @Test
    void aLinkIsNotMistakenForASlashSegment() {
        Graph graph = PipelineText.read("/download " + URL + " /audio mp3");

        assertEquals(URL, graph.source().orElseThrow().url());
        assertEquals(AudioFormat.MP3, nodes(graph, Node.Audio.class).getFirst().format());
    }

    @Test
    void heightAndContainerAreOptionalAndOrderFree() {
        assertEquals(720, encodeOf("/download " + URL + " /encode 720p mp4").height());
        assertEquals(720, encodeOf("/download " + URL + " /encode mp4 720p").height());
        assertEquals(Container.MP4, encodeOf("/download " + URL + " /encode 720p").container());
        assertEquals(2160, encodeOf("/download " + URL + " /encode 4k").height());
        assertInstanceOf(Node.Encode.class, encodeOf("/download " + URL + " /encode"));
    }

    @Test
    void aProfileBringsItsOwnHeightAndSqueeze() {
        assertEquals(Quality.LOW, encodeOf("/download " + URL + " /encode LOW").quality());
        assertEquals(360, encodeOf("/download " + URL + " /encode LOW").height());
        assertEquals(1080, encodeOf("/download " + URL + " /encode HIGH").height());
        assertEquals(Quality.STANDARD, encodeOf("/download " + URL + " /encode").quality(),
                "no profile named is the standard one, not none");
        assertNull(encodeOf("/download " + URL + " /encode MAX").height(),
                "MAX keeps whatever the source is");
    }

    @Test
    void aHeightWrittenNextToAProfileWins() {
        Node.Encode encode = encodeOf("/download " + URL + " /encode HIGH 480p");

        assertEquals(480, encode.height(), "the person said 480, the profile only says how hard");
        assertEquals(Quality.HIGH, encode.quality());
    }

    @Test
    void profilesAreCaseInsensitiveAndForgiveTheCommonMisspelling() {
        assertEquals(Quality.STANDARD, encodeOf("/download " + URL + " /encode standard").quality());
        assertEquals(Quality.STANDARD, encodeOf("/download " + URL + " /encode STANDART").quality());
        assertEquals(Quality.MAX, encodeOf("/download " + URL + " /encode max").quality());
    }

    @Test
    void theOtherNamesForEncodeAllMeanEncode() {
        for (String verb : List.of("encode", "transcode", "compress", "convert")) {
            assertEquals(480, encodeOf("/download " + URL + " /" + verb + " 480p").height(), verb);
        }
    }

    @Test
    void anyEvenHeightIsAcceptedAndAnOddOneIsExplained() {
        assertEquals(320, encodeOf("/download " + URL + " /encode 320").height());
        assertEquals(546, encodeOf("/download " + URL + " /encode 546p").height());

        var refused = assertThrows(Pipeline.Invalid.class,
                () -> PipelineText.read("/download " + URL + " /encode 321"));
        assertTrue(refused.getMessage().contains("odd"), refused.getMessage());

        var tooSmall = assertThrows(Pipeline.Invalid.class,
                () -> PipelineText.read("/download " + URL + " /encode 12"));
        assertTrue(tooSmall.getMessage().contains("12"), tooSmall.getMessage());
    }

    private static Node.Encode encodeOf(String text) {
        return nodes(PipelineText.read(text), Node.Encode.class).getFirst();
    }

    private static List<Node.Cut> cuts(Graph graph) {
        return nodes(graph, Node.Cut.class);
    }

    private static <T extends Node> List<T> nodes(Graph graph, Class<T> type) {
        return graph.nodes().stream().filter(type::isInstance).map(type::cast).toList();
    }
}
