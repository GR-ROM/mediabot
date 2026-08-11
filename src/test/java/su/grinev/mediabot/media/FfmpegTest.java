package su.grinev.mediabot.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the two ffmpeg jobs meet.
 *
 * <p>Merging names its output after the height it fetched, and re-encoding writes beside its own
 * source. Left alone those two conventions produce {@code Title (1080p)_480p.mp4} — and, when the
 * heights happen to agree, one name for both files, which is ffmpeg reading and writing the same
 * bytes.
 */
class FfmpegTest {

    @Test
    void aHeightIsAClaimAboutTheShortSide() {
        // Measured on a real reel: 1080x1920 asked for at 720p came out 406x720 — a third of the
        // pixels — because the filter set the height. On a phone that arrives looking like a bad
        // bitrate rather than like the wrong size, which is why it went unnoticed.
        String filter = Ffmpeg.scaleShortSideTo(720);

        assertTrue(filter.contains("gt(iw\\,ih)"),
                "which side is short depends on the video: " + filter);
        assertTrue(filter.contains("\\,"),
                "a bare comma ends the filter halfway through the expression: " + filter);
        assertTrue(filter.contains("720"), filter);
        // -2 rather than -1 on the free side: H.264 wants even dimensions.
        assertTrue(filter.contains("-2"), filter);
    }

    @Test
    void theNamingAndTheScalingAgreeAboutWhatAHeightMeans() {
        // The filename already called a 1080x1920 video 1080p rather than 1920p. The scaling now
        // says the same thing, and a test says so because the two live in different classes.
        MediaInfo.Format portrait = new MediaInfo.Format("v", "mp4", 1080, 1920, 30, 0, "avc1", "none");

        assertEquals(1080, portrait.shortSide());
        assertTrue(Ffmpeg.scaleShortSideTo(portrait.shortSide()).contains("1080"));
    }

    @Test
    void everyH264ContainerSaysWhatBitDepthItWants() {
        // Left to itself, x264 encodes ten-bit output from a ten-bit source, in the High 10 profile
        // — which nothing Apple makes decodes, and which arrives looking like a corrupt file rather
        // than an unsupported one.
        for (Container container : java.util.List.of(Container.MP4, Container.MOV, Container.MKV)) {
            String written = String.join(" ", container.videoCodec());
            assertTrue(written.contains("-pix_fmt yuv420p"), container + ": " + written);
            assertTrue(written.contains("-profile:v high"), container + ": " + written);
        }
    }

    @Test
    void theOneThatIsNotH264IsLeftAlone() {
        // VP9 has no such trap, and forcing a pixel format on it would be cargo cult.
        assertFalse(String.join(" ", Container.WEBM.videoCodec()).contains("pix_fmt"));
    }

    @Test
    void theHeightAMergeNamedIsNotCarriedIntoTheReEncodedFile() {
        assertEquals("A video_480p.mp4", Ffmpeg.scaledName("A video (1080p)", 480));
        assertEquals("A video_480p.mp4", Ffmpeg.scaledName("A video", 480));
    }

    @Test
    void aTitleThatLooksLikeAHeightIsLeftAlone() {
        assertEquals("Shot on 35mm (1080p) part 2_720p.mp4",
                Ffmpeg.scaledName("Shot on 35mm (1080p) part 2", 720));
    }

    @Test
    void reEncodingToTheHeightItWasFetchedAtStillChangesTheName() {
        assertNotEquals("A video (720p).mp4", Ffmpeg.scaledName("A video (720p)", 720));
    }
}
