package su.grinev.mediabot.jobs;

import org.junit.jupiter.api.Test;
import su.grinev.mediabot.media.MediaInfo;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A ceiling is not a request, and the difference is the commonest message the bot gets.
 *
 * <p>"Send me this link" carries no height at all. Capping it to what the chat may have turned it
 * into "give me 2160p", and the guard that refuses upscales then refused every video shorter than
 * the ceiling — which is nearly all of them. The guard is right; what it was being shown was not.
 */
class PlainDownloadCeilingTest {

    private static final MediaInfo SHORT_VIDEO = new MediaInfo("t", "u", 60, false, List.of(
            new MediaInfo.Format("18", "mp4", 360, 30, 10_000_000L, "avc1", "mp4a"),
            new MediaInfo.Format("133", "mp4", 240, 30, 5_000_000L, "avc1", "none"),
            new MediaInfo.Format("160", "mp4", 144, 30, 2_000_000L, "avc1", "none")));

    private final DownloadRequests requests = new DownloadRequests(null, null, null);

    @Test
    void askingForNoHeightIsNeverAnUpscale() {
        assertNull(requests.upscaleRefusal(null, SHORT_VIDEO),
                "a bare link asks for whatever there is, which a 360p video always has");
    }

    @Test
    void askingForMoreThanTheSourceHasIsStillRefused() {
        String refusal = requests.upscaleRefusal(2160, SHORT_VIDEO);

        assertNotNull(refusal, "somebody who typed 2160p is asking for something that is not there");
    }

    @Test
    void askingForLessThanTheSourceHasIsFine() {
        assertNull(requests.upscaleRefusal(240, SHORT_VIDEO));
        assertNull(requests.upscaleRefusal(360, SHORT_VIDEO));
    }

    @Test
    void aCeilingStillBoundsWhatGetsFetched() {
        JobSpec capped = JobSpec.download(1, "https://youtu.be/x", null).cappedAt(720);

        // The number is on the spec so the downloader stops at it — it just is not the number the
        // upscale guard is shown, because nobody asked for it.
        assertNotNull(capped.maxHeight());
    }
}
