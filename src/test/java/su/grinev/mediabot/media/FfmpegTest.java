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
