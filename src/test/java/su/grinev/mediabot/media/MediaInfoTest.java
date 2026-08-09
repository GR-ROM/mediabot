package su.grinev.mediabot.media;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the bot is allowed to claim about a video before it has downloaded a byte of it. */
class MediaInfoTest {

    @Test
    void heightsComeBackTallestFirstAndWithoutDuplicates() {
        MediaInfo info = new MediaInfo("t", "u", 100, false, List.of(
                video("a", 720, 1_000_000),
                video("b", 1080, 2_000_000),
                video("c", 720, 900_000),
                audio("d", 300_000)));

        assertEquals(List.of(1080, 720), info.heights());
        assertEquals(1080, info.maxHeight());
        assertTrue(info.hasAudioOnly());
    }

    @Test
    void separateStreamsAreAddedTogether() {
        // Video and audio arrive as separate files and are muxed, so quoting only the video size
        // would understate what the user actually receives.
        MediaInfo info = new MediaInfo("t", "u", 100, false, List.of(
                video("v", 720, 10_000_000),
                audio("a", 2_000_000)));

        assertEquals(12_000_000L, info.estimatedSize(720).orElseThrow());
    }

    @Test
    void aCeilingPicksTheTallestStreamUnderIt() {
        MediaInfo info = new MediaInfo("t", "u", 100, false, List.of(
                video("v1", 1080, 20_000_000),
                video("v2", 720, 10_000_000),
                video("v3", 480, 5_000_000),
                audio("a", 1_000_000)));

        assertEquals("v2", info.pickVideo(720).orElseThrow().id());
        assertEquals(11_000_000L, info.estimatedSize(720).orElseThrow());
        assertEquals(21_000_000L, info.estimatedSize(null).orElseThrow(),
                "no ceiling means the best available");
    }

    @Test
    void severalCodecsAtOneHeightAreNotGuessedBetween() {
        MediaInfo info = new MediaInfo("t", "u", 100, false, List.of(
                new MediaInfo.Format("136", "mp4", 720, 25, 167_302_942L, "avc1.64001f", "none"),
                new MediaInfo.Format("247", "webm", 720, 25, 90_721_080L, "vp9", "none"),
                new MediaInfo.Format("398", "mp4", 720, 25, 64_753_419L, "av01.0.05M.08", "none"),
                new MediaInfo.Format("140", "m4a", 0, 0, 20_427_588L, "none", "mp4a.40.2"),
                new MediaInfo.Format("251", "webm", 0, 0, 20_080_247L, "none", "opus")));

        assertEquals("136", info.pickVideo(720).orElseThrow().id(),
                "H.264 is the copy every Telegram client plays inline");
        assertEquals("140", info.pickAudio().orElseThrow().id(),
                "an mp4 carrying Opus arrives as a video with no sound");
        assertEquals(167_302_942L + 20_427_588L, info.estimatedSize(720).orElseThrow(),
                "the estimate has to describe the streams that will be fetched, not the "
                        + "largest ones at that height");
    }

    @Test
    void aSourceThatWillBeReEncodedIsTakenInTheCodecCheapestToDecode() {
        // This used to take the smallest track, on the reasoning that nothing about the fetched
        // codec survives a re-encode. True of the picture and false of the clock: the re-encode has
        // to decode every frame first, and AV1 costs several times H.264 to decode. On two cores a
        // 39-minute 1080p AV1 spent seven minutes being decoded — the bytes it saved coming down
        // the wire were paid back many times over.
        MediaInfo info = new MediaInfo("t", "u", 1262, false, List.of(
                new MediaInfo.Format("137", "mp4", 1080, 25, 312_000_000L, "avc1.640028", "none"),
                new MediaInfo.Format("248", "webm", 1080, 25, 155_000_000L, "vp9", "none"),
                new MediaInfo.Format("399", "mp4", 1080, 25, 110_000_000L, "av01.0.08M.08", "none")));

        assertEquals("137", info.pickVideo(null, MediaInfo.Purpose.RE_ENCODING).orElseThrow().id());
        assertEquals("137", info.pickVideo(null).orElseThrow().id(),
                "a file that goes to the chat as it is still has to play there");
    }

    @Test
    void theSmallestOfTwoEqualCodecsIsTakenForReEncoding() {
        MediaInfo info = new MediaInfo("t", "u", 100, false, List.of(
                new MediaInfo.Format("fat", "mp4", 720, 25, 90_000_000L, "av01", "none"),
                new MediaInfo.Format("lean", "mp4", 720, 25, 60_000_000L, "av01", "none")));

        assertEquals("lean", info.pickVideo(null, MediaInfo.Purpose.RE_ENCODING).orElseThrow().id());
    }

    @Test
    void aTallerSourceStillBeatsASmallerCodec() {
        MediaInfo info = new MediaInfo("t", "u", 100, false, List.of(
                new MediaInfo.Format("1080", "mp4", 1080, 25, 300_000_000L, "avc1", "none"),
                new MediaInfo.Format("480", "mp4", 480, 25, 30_000_000L, "av01", "none")));

        assertEquals("1080", info.pickVideo(null, MediaInfo.Purpose.RE_ENCODING).orElseThrow().id(),
                "the codec is the tie-break, not the rule — height comes first either way");
    }

    @Test
    void moreFramesWinBeforeTheCodecDoes() {
        MediaInfo info = new MediaInfo("t", "u", 100, false, List.of(
                new MediaInfo.Format("30fps", "mp4", 1080, 30, 5_000_000, "avc1", "none"),
                new MediaInfo.Format("60fps", "webm", 1080, 60, 8_000_000, "vp9", "none")));

        assertEquals("60fps", info.pickVideo(null).orElseThrow().id());
    }

    @Test
    void aHostWithOneCombinedStreamIsStillDownloadable() {
        MediaInfo info = new MediaInfo("t", "u", 100, false, List.of(
                new MediaInfo.Format("18", "mp4", 360, 25, 48_984_805L, "avc1", "mp4a")));

        assertTrue(info.pickVideo(360).isEmpty(), "there is no video-only stream to merge");
        assertEquals("18", info.pickProgressive(360).orElseThrow().id());
        assertEquals(48_984_805L, info.estimatedSize(360).orElseThrow());
    }

    @Test
    void aHostThatPublishesNoSizesIsNotGuessedAt() {
        MediaInfo info = new MediaInfo("t", "u", 100, false, List.of(video("v", 720, 0)));

        assertTrue(info.estimatedSize(720).isEmpty(),
                "no figure is honest; a made-up one would be quoted to the user as fact");
    }

    private static MediaInfo.Format video(String id, int height, long size) {
        return new MediaInfo.Format(id, "mp4", height, 30, size, "avc1.4d401e", "none");
    }

    private static MediaInfo.Format audio(String id, long size) {
        return new MediaInfo.Format(id, "m4a", 0, 0, size, "none", "mp4a.40.2");
    }
}
