package su.grinev.mediabot.jobs;

import org.junit.jupiter.api.Test;
import su.grinev.mediabot.graph.Node;
import su.grinev.mediabot.graph.PipelineText;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The ceiling a chat may not ask above, applied to the graph rather than to the words.
 *
 * <p>That is the whole property worth testing: there are four ways to ask for a height — a bare
 * link, a slash command, a chain, and whatever the model returns — and a limit that only understood
 * one of them would be a limit in name.
 */
class HeightCeilingTest {

    private static final long CHAT = 1;
    private static final String URL = "https://www.youtube.com/watch?v=jNQXAC9IVRw";

    @Test
    void aPlainDownloadMeansWhateverTheSourceIsAndSoBecomesTheCeiling() {
        JobSpec capped = JobSpec.download(CHAT, URL, null).cappedAt(720);

        assertEquals(720, capped.maxHeight(), "'best available' is exactly what a ceiling bounds");
    }

    @Test
    void aHeightBelowTheCeilingIsLeftAlone() {
        assertEquals(480, JobSpec.download(CHAT, URL, 480).cappedAt(720).maxHeight());
    }

    @Test
    void aHeightAboveTheCeilingComesDownToIt() {
        assertEquals(720, JobSpec.download(CHAT, URL, 2160).cappedAt(720).maxHeight());
    }

    @Test
    void aChainCannotWalkAroundIt() {
        JobSpec capped = JobSpec.of(CHAT, PipelineText.read("/download " + URL + " /encode 4k"))
                .cappedAt(720);

        assertEquals(720, encodeIn(capped).height(),
                "the encode is where the height actually lands, so that is what has to be clamped");
        assertEquals(720, fetchIn(capped).maxHeight(),
                "and fetching 2160p to scale it down would cost the bytes anyway");
    }

    @Test
    void maxKeepsTheSourceSizeAndSoIsBoundedToo() {
        JobSpec capped = JobSpec.of(CHAT, PipelineText.read("/download " + URL + " /encode MAX"))
                .cappedAt(720);

        assertEquals(720, encodeIn(capped).height(), "MAX means whatever the source is, which is unbounded");
    }

    @Test
    void anOwnerCeilingLetsThroughWhatAGuestCeilingWouldNot() {
        JobSpec asked = JobSpec.download(CHAT, URL, 2160);

        assertEquals(2160, asked.cappedAt(2160).maxHeight());
        assertEquals(720, asked.cappedAt(720).maxHeight());
    }

    @Test
    void everyCutSurvivesTheCapping() {
        JobSpec capped = JobSpec.of(CHAT, PipelineText.read(
                "/download " + URL + " /cut 1:00-2:00 2:00-2:20 /encode 1080p")).cappedAt(720);

        assertEquals(2, capped.graph().nodes().stream()
                .filter(Node.Cut.class::isInstance).count(), "clamping is not a rewrite of the work");
        assertEquals(2, capped.graph().outputs().size());
        capped.graph().nodes().stream()
                .filter(Node.Encode.class::isInstance).map(Node.Encode.class::cast)
                .forEach(encode -> assertEquals(720, encode.height()));
    }

    private static Node.Encode encodeIn(JobSpec spec) {
        return first(spec, Node.Encode.class);
    }

    private static Node.Fetch fetchIn(JobSpec spec) {
        return first(spec, Node.Fetch.class);
    }

    private static <T extends Node> T first(JobSpec spec, Class<T> type) {
        List<T> found = spec.graph().nodes().stream()
                .filter(type::isInstance).map(type::cast).toList();
        return found.getFirst();
    }
}
