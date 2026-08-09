package su.grinev.mediabot.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
