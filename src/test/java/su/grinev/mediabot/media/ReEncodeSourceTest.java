package su.grinev.mediabot.media;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which stream to fetch when it is going to be re-encoded anyway.
 *
 * <p>Both answers here used to be the other way round, and both were defensible until the work ran
 * on two cores: the smallest source is the most expensive one to decode, and the tallest source is
 * a quarter of a gigabyte fetched to be thrown away. What is cheap to produce a 360p file from is
 * a small avc1, not a large av01.
 */
class ReEncodeSourceTest {

    private static final MediaInfo YOUTUBE = new MediaInfo("t", "u", 2312, false, List.of(
            video("137", 1080, "avc1.640028", 900_000_000),
            video("399", 1080, "av01.0.09M.08", 253_000_000),
            video("248", 1080, "vp09.00.40.08", 400_000_000),
            video("136", 720, "avc1.4d401f", 500_000_000),
            video("398", 720, "av01.0.08M.08", 140_000_000),
            video("135", 480, "avc1.4d401e", 250_000_000),
            video("134", 360, "avc1.4d401e", 120_000_000),
            audio("140", 3_000_000)));

    @Test
    void theSourceIsTheSmallestRenditionThatStillReachesTheTarget() {
        assertEquals(360, YOUTUBE.sourceHeightFor(360));
        assertEquals(480, YOUTUBE.sourceHeightFor(480));
        assertEquals(720, YOUTUBE.sourceHeightFor(500), "nothing is 500, so the next one up");
    }

    @Test
    void aTargetTallerThanAnythingPublishedFallsBackToTheTallest() {
        assertEquals(1080, YOUTUBE.sourceHeightFor(2160));
    }

    @Test
    void reEncodingPrefersTheCodecThatIsCheapestToDecode() {
        MediaInfo.Format picked = YOUTUBE
                .pickVideo(1080, MediaInfo.Purpose.RE_ENCODING)
                .orElseThrow();

        assertEquals("137", picked.id(), "avc1 decodes in a fraction of what av01 costs");
    }

    @Test
    void deliveryStillPrefersWhatPlaysEverywhere() {
        MediaInfo.Format picked = YOUTUBE
                .pickVideo(720, MediaInfo.Purpose.DELIVERY)
                .orElseThrow();

        assertEquals("136", picked.id());
    }

    @Test
    void theTwoRulesTogetherTurnAQuarterGigabyteIntoATwelfth() {
        int source = YOUTUBE.sourceHeightFor(360);
        MediaInfo.Format picked = YOUTUBE
                .pickVideo(source, MediaInfo.Purpose.RE_ENCODING)
                .orElseThrow();

        assertEquals("134", picked.id());
        assertEquals(120_000_000, picked.sizeBytes(),
                "against 253 MB of 1080p av01, which is what this used to fetch");
    }

    private static MediaInfo.Format video(String id, int height, String codec, long size) {
        return new MediaInfo.Format(id, "mp4", height, 30, size, codec, "none");
    }

    private static MediaInfo.Format audio(String id, long size) {
        return new MediaInfo.Format(id, "m4a", 0, 0, size, "none", "mp4a.40.2");
    }
}
