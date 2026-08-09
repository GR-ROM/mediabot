package su.grinev.mediabot.media;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VideoDownloaderNamingTest {

    @Test
    void aVerticalVideoIsNamedByItsShortSide() {
        MediaInfo info = info("Video by dronelandbrasil");
        MediaInfo.Format reel = format(1080, 1920);

        assertEquals("Video by dronelandbrasil (1080p).mp4",
                VideoDownloader.fileName(info, reel));
    }

    @Test
    void aLandscapeVideoIsStillNamedByItsHeight() {
        MediaInfo info = info("A wide one");

        assertEquals("A wide one (720p).mp4",
                VideoDownloader.fileName(info, format(1280, 720)));
    }

    @Test
    void aSquareVideoUsesTheSideBothDimensionsShare() {
        MediaInfo info = info("Square");

        assertEquals("Square (1080p).mp4",
                VideoDownloader.fileName(info, format(1080, 1080)));
    }

    @Test
    void withoutAWidthTheHeightIsAllThereIs() {
        MediaInfo info = info("No width published");

        assertEquals("No width published (1920p).mp4",
                VideoDownloader.fileName(info, format(0, 1920)));
    }

    private static MediaInfo info(String title) {
        return new MediaInfo(title, "u", 30, false, List.of());
    }

    private static MediaInfo.Format format(int width, int height) {
        return new MediaInfo.Format("f", "mp4", width, height, 24, 1_000_000, "vp9", "none");
    }
}
