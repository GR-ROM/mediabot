package su.grinev.mediabot.jobs;

import org.junit.jupiter.api.Test;
import su.grinev.mediabot.media.MediaInfo;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one guarantee the whole design is arranged around: you cannot be given a height the source
 * does not have.
 *
 * <p>Tested against {@link DownloadRequests} directly, with no model and no network anywhere near
 * it, because that is the point. If this rule lived in the system prompt it would hold only while
 * the model was up and paying attention — and the moment it matters most is the moment the model is
 * unreachable and the deterministic router is doing the queueing.
 */
class UpscaleRefusalTest {

    /** Only the pure rule is exercised, so the collaborators are never touched. */
    private final DownloadRequests requests = new DownloadRequests(null, null, null);

    @Test
    void askingForMoreThanTheSourceHasIsRefused() {
        MediaInfo source480 = video(480, 360);

        String refusal = requests.upscaleRefusal(720, source480);

        assertNotNull(refusal, "720p out of a 480p source has to be refused");
        assertTrue(refusal.contains("480p"), "the refusal must state what the source actually is");
        assertTrue(refusal.contains("720p"), "and what was asked for");
        assertTrue(refusal.contains("Available: 480p, 360p"),
                "and what can be had instead, or the user is left with no way forward: " + refusal);
    }

    @Test
    void askingForWhatExistsIsAllowed() {
        MediaInfo source1080 = video(1080, 720, 480);

        assertNull(requests.upscaleRefusal(720, source1080));
        assertNull(requests.upscaleRefusal(1080, source1080), "asking for exactly the top is fine");
    }

    @Test
    void askingForNothingInParticularIsAlwaysAllowed() {
        // Absent means "best available", which no source can fail to satisfy.
        assertNull(requests.upscaleRefusal(null, video(360)));
    }

    @Test
    void anUnprobedVideoIsNotRefused() {
        // A probe that failed must not become a refusal to download: yt-dlp falls back to the best
        // available format, so the worst case is lower quality, not a request that goes nowhere.
        assertNull(requests.upscaleRefusal(1080, null));
    }

    @Test
    void aHostThatPublishesNoHeightsIsNotRefused() {
        MediaInfo noHeights = new MediaInfo("x", "y", 60, false,
                List.of(new MediaInfo.Format("0", "mp4", 0, 30, 1000, "avc1", "mp4a")));

        assertNull(requests.upscaleRefusal(1080, noHeights),
                "no stated height is not the same as a stated small one");
    }

    private static MediaInfo video(int... heights) {
        List<MediaInfo.Format> formats = new java.util.ArrayList<>();
        for (int height : heights) {
            formats.add(new MediaInfo.Format("v" + height, "mp4", height, 30,
                    height * 100_000L, "avc1", "none"));
        }
        formats.add(new MediaInfo.Format("audio", "m4a", 0, 0, 3_000_000, "none", "mp4a"));
        return new MediaInfo("A video", "A channel", 252, false, formats);
    }
}
