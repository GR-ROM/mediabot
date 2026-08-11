package su.grinev.mediabot.media;

import org.junit.jupiter.api.Test;
import su.grinev.mediabot.jobs.DownloadRequests;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A height is a claim about the short side, everywhere and not only where it was noticed.
 *
 * <p>This was fixed three times in three places before it was fixed as one idea. The filename knew
 * first — a phone recording is 1080p and not 1920p. Then the scaling, which had been setting the
 * frame height and turning a 1080x1920 reel into 406x720. This is the rest of it: the ceiling a
 * request is checked against, the sizes a refusal offers instead, and which rendition is picked.
 *
 * <p>Measured on a real reel throughout: everything here is what Instagram actually serves.
 */
class VerticalVideoTest {

    /** What a host publishes for one vertical video: 9:16, so the short side is the width. */
    private static MediaInfo portrait(int... shortSides) {
        List<MediaInfo.Format> formats = new java.util.ArrayList<>();
        for (int side : shortSides) {
            formats.add(new MediaInfo.Format("v" + side, "mp4", side, side * 16 / 9, 30,
                    side * 100_000L, "avc1", "none"));
        }
        formats.add(new MediaInfo.Format("a", "m4a", 0, 0, 0, 200_000L, "none", "mp4a"));
        return new MediaInfo("Video by explainingwhymedia", "explainingwhymedia", 43, false, formats);
    }

    private static MediaInfo landscape(int... heights) {
        List<MediaInfo.Format> formats = new java.util.ArrayList<>();
        for (int height : heights) {
            formats.add(new MediaInfo.Format("v" + height, "mp4", height * 16 / 9, height, 30,
                    height * 100_000L, "avc1", "none"));
        }
        return new MediaInfo("A wide video", "somebody", 60, false, formats);
    }

    @Test
    void theCeilingIsTheShortSideAndNotTheFrameHeight() {
        // 1080x1920. Measured as the frame height this said 1920, and a request for 1440p sailed
        // through the upscale check on a video only 1080 across.
        assertEquals(1080, portrait(1080, 720, 480).maxHeight());
        assertEquals(1080, landscape(1080, 720).maxHeight());
    }

    @Test
    void askingForMoreThanTheShortSideIsRefused() {
        var requests = new DownloadRequests(null, null, null);
        MediaInfo reel = portrait(1080, 720);

        String refusal = requests.upscaleRefusal(1440, reel);

        assertNotNull(refusal, "1440p of a 1080-wide reel is a stretch, not a download");
        assertTrue(refusal.contains("1080p"), refusal);
        assertNull(requests.upscaleRefusal(1080, reel), "and its own size still goes through");
    }

    @Test
    void aRefusalQuotesTheNumbersAPersonWouldHaveTyped() {
        // Not 1920p, 1280p — nobody asks for those, and they are the same wrong number the filename
        // used to carry.
        assertEquals(List.of(1080, 720, 480), portrait(1080, 720, 480).heights());
    }

    @Test
    void aCeilingPicksTheRenditionOfThatShortSide() {
        MediaInfo reel = portrait(1080, 720, 480);

        MediaInfo.Format picked = reel.pickVideo(720).orElseThrow();

        assertEquals(720, picked.shortSide(), "720 has to mean 720x1280, not 405x720");
        assertEquals(1280, picked.height());
    }

    @Test
    void aCeilingStillWorksTheOrdinaryWayRoundForAWideVideo() {
        MediaInfo wide = landscape(1080, 720, 480);

        MediaInfo.Format picked = wide.pickVideo(720).orElseThrow();

        assertEquals(720, picked.height());
        assertEquals(1280, picked.width());
    }

    @Test
    void theSourceFetchedForAReEncodeIsMeasuredTheSameWay() {
        MediaInfo reel = portrait(1080, 720, 480);

        assertEquals(720, reel.sourceHeightFor(720));
        assertEquals(1080, reel.sourceHeightFor(1000), "the smallest that can still produce it");
    }

    @Test
    void aSourceSmallerThanTheTargetIsNotStretched() {
        // The filter's own guard, which is the other half: 480x854 asked for at 720p stays as it is
        // rather than becoming 720x1282.
        String filter = Ffmpeg.scaleShortSideTo(720);

        assertTrue(filter.contains("min(720"), filter);
    }
}
