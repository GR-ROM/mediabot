package su.grinev.mediabot.route;

import org.junit.jupiter.api.Test;
import su.grinev.mediabot.graph.Node;
import su.grinev.mediabot.jobs.JobKind;
import su.grinev.mediabot.jobs.JobSpec;
import su.grinev.mediabot.media.Container;
import su.grinev.mediabot.media.Quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The download an Apple device will open.
 *
 * <p>The point of it is that the source stops mattering. A plain {@code /download} re-encodes
 * nothing — whatever the host served is what arrives, merged into an mp4 container — so an HEVC
 * stream, a ten-bit one, or an audio codec iOS declines all produce a file whose extension promises
 * something it cannot deliver. This one always re-encodes, which is the only way to answer "it will
 * not play on my phone" without knowing what the host sent.
 */
class AppleDownloadTest {

    private static final long CHAT = 600000002L;
    private static final String URL = "https://www.instagram.com/reel/Dby2tKnsoqX/";

    private final RequestRouter router = new RequestRouter();

    private JobSpec fetch(String text) {
        return ((Request.Fetch) router.parse(CHAT, text).orElseThrow()).spec();
    }

    private static Node.Encode encodeOf(JobSpec spec) {
        return spec.graph().nodes().stream()
                .filter(Node.Encode.class::isInstance)
                .map(Node.Encode.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("nothing is being re-encoded: " + spec.graph()));
    }

    @Test
    void itAlwaysReEncodesRatherThanHandingOverWhatArrived() {
        JobSpec spec = fetch("/idownload " + URL);

        assertEquals(JobKind.TRANSCODE, spec.scenario());
        assertEquals(Container.MP4, encodeOf(spec).container(),
                "mp4 is the one container an Apple device opens without being asked twice");
        assertEquals(3, spec.graph().nodes().size(), "fetch, encode, publish");
    }

    @Test
    void itAimsAtWhatEverythingElseAimsAt() {
        // 720p, because that is what the rest of the bot means by a default: the guest ceiling, the
        // standard profile and a transcode with no height all say it. Picking 1080 here would also
        // have been invisible to a guest, whose ceiling caps it straight back to 720.
        assertEquals(720, encodeOf(fetch("/idownload " + URL)).height());
        assertEquals(Quality.DEFAULT, encodeOf(fetch("/idownload " + URL)).quality());
    }

    @Test
    void moreThanTheDefaultIsStillOneWordAway() {
        assertEquals(1080, encodeOf(fetch("/idownload 1080p " + URL)).height());
    }

    @Test
    void aHeightWrittenNextToItStillWins() {
        assertEquals(720, encodeOf(fetch("/idownload 720p " + URL)).height());
        assertEquals(480, encodeOf(fetch("/idownload " + URL + " 480p")).height(),
                "argument order is free here as everywhere else");
    }

    @Test
    void theSlashFormHasTheObviousSecondSpelling() {
        assertEquals(JobKind.TRANSCODE, fetch("/iphone " + URL).scenario());
    }

    @Test
    void askingForItInWordsReachesTheSamePlace() {
        for (String written : java.util.List.of(
                URL + " for my iphone",
                "download this for the ipad " + URL,
                "grab it for apple " + URL)) {

            assertEquals(JobKind.TRANSCODE, fetch(written).scenario(), written);
            assertEquals(Container.MP4, encodeOf(fetch(written)).container(), written);
        }
    }

    @Test
    void anOrdinaryDownloadIsStillNotAReEncode() {
        // The distinction is the whole point: re-encoding every download would spend minutes of a
        // two-core box on files that were already fine.
        JobSpec spec = fetch(URL);

        assertEquals(JobKind.DOWNLOAD, spec.scenario());
        assertEquals(2, spec.graph().nodes().size(), "fetch and publish, nothing between them");
    }

    @Test
    void itIsInTheHelpBecauseTheHelpIsGeneratedFromTheCommands() {
        assertTrue(Command.everyday().contains("/idownload"), Command.everyday());
        assertTrue(Command.everyday().contains("Apple"), Command.everyday());
    }
}
