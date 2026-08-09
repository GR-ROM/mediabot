package su.grinev.mediabot.graph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import su.grinev.mediabot.media.DownloadProgress;
import su.grinev.mediabot.media.Container;
import su.grinev.mediabot.media.Ffmpeg;
import su.grinev.mediabot.media.MediaInfo;
import su.grinev.mediabot.media.Trim;
import su.grinev.mediabot.media.VideoDownloader;
import su.grinev.mediabot.media.YtDlp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The walk itself: which node ran, on whose output, and what was thrown away afterwards.
 */
class GraphRunnerTest {

    private static final String URL = "https://www.youtube.com/watch?v=jNQXAC9IVRw";

    @TempDir
    Path work;

    private VideoDownloader downloader;
    private Ffmpeg ffmpeg;
    private GraphRunner runner;

    @BeforeEach
    void setUp() throws Exception {
        downloader = mock(VideoDownloader.class);
        ffmpeg = mock(Ffmpeg.class);
        YtDlp ytDlp = mock(YtDlp.class);
        when(ytDlp.probe(any())).thenThrow(new java.io.IOException("no network in a unit test"));
        runner = new GraphRunner(downloader, ffmpeg, ytDlp);
    }

    @Test
    void aPlainDownloadFetchesForDeliveryAndPublishesTheFile() throws Exception {
        Path fetched = file("video.mp4");
        when(downloader.video(eq(URL), any(), any(), any(), any())).thenReturn(fetched);

        List<GraphRunner.Output> outputs = run("/download " + URL);

        assertEquals(1, outputs.size());
        assertEquals(fetched, outputs.getFirst().file());
        verify(downloader).video(eq(URL), any(), any(), any(), eq(MediaInfo.Purpose.DELIVERY));
    }

    @Test
    void aFetchThatFeedsAnEncodePicksItsStreamsForReEncoding() throws Exception {
        Path fetched = file("video.mp4");
        Path encoded = file("video-480.mp4");
        when(downloader.video(any(), any(), any(), any(), any())).thenReturn(fetched);
        when(ffmpeg.encode(eq(fetched), eq(480), eq(Container.MP4), any(), anyInt(), any())).thenReturn(encoded);

        List<GraphRunner.Output> outputs = run("/download " + URL + " /encode 480p");

        assertEquals(encoded, outputs.getFirst().file());
        verify(downloader).video(any(), any(), any(), any(), eq(MediaInfo.Purpose.RE_ENCODING));
    }

    @Test
    void audioAloneNeverFetchesTheVideoToThrowItAway() throws Exception {
        Path track = file("track.mp3");
        when(downloader.audio(eq(URL), eq("mp3"), any(), any())).thenReturn(track);

        List<GraphRunner.Output> outputs = run("/download " + URL + " /audio mp3");

        assertEquals(track, outputs.getFirst().file());
        verify(downloader, never()).video(any(), any(), any(), any(), any());
        verify(ffmpeg, never()).extractAudio(any(), any(), anyInt(), any());
    }

    @Test
    void aCutFeedingAnEncodeIsOnePassAndNotTwo() throws Exception {
        Path fetched = file("video.mp4");
        Path firstOut = file("piece1-720.mp4");
        Path secondOut = file("piece2-720.mp4");

        when(downloader.video(any(), any(), any(), any(), any())).thenReturn(fetched);
        when(ffmpeg.cutAndEncode(eq(fetched), any(), any(), any(), any(), eq(1), any()))
                .thenReturn(firstOut);
        when(ffmpeg.cutAndEncode(eq(fetched), any(), any(), any(), any(), eq(2), any()))
                .thenReturn(secondOut);

        List<GraphRunner.Output> outputs =
                run("/download " + URL + " /cut 1:00-2:00 2:00-2:20 /encode mp4 720p");

        assertEquals(List.of(firstOut, secondOut),
                outputs.stream().map(GraphRunner.Output::file).toList());
        verify(ffmpeg).cutAndEncode(eq(fetched), eq(new Trim(60, 120)), eq(720),
                eq(Container.MP4), any(), eq(1), any());
        verify(ffmpeg).cutAndEncode(eq(fetched), eq(new Trim(120, 140)), eq(720),
                eq(Container.MP4), any(), eq(2), any());
        verify(ffmpeg, never()).cut(any(), any(), anyInt(), anyInt(), any());
        verify(ffmpeg, never()).encode(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void aCutWithNothingToEncodeStaysACopy() throws Exception {
        Path fetched = file("video.mp4");
        Path piece = file("piece1.mp4");

        when(downloader.video(any(), any(), any(), any(), any())).thenReturn(fetched);
        when(ffmpeg.cut(eq(fetched), any(), eq(1), anyInt(), any())).thenReturn(piece);

        run("/download " + URL + " /cut 0:00-0:10");

        verify(ffmpeg).cut(eq(fetched), eq(new Trim(0, 10)), eq(1), anyInt(), any());
        verify(ffmpeg, never()).cutAndEncode(any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void whatWasPublishedStaysAndEverythingElseIsDeleted() throws Exception {
        Path fetched = file("video.mp4");
        Path encoded = file("piece1-480.mp4");

        when(downloader.video(any(), any(), any(), any(), any())).thenReturn(fetched);
        when(ffmpeg.cutAndEncode(any(), any(), any(), any(), any(), anyInt(), any())).thenReturn(encoded);

        run("/download " + URL + " /cut 0:00-0:10 /encode 480p");

        assertTrue(Files.exists(encoded), "the published file has to survive");
        assertFalse(Files.exists(fetched), "the source is not served and costs the most");
    }

    @Test
    void joiningLeavesOneFileAndOneLink() throws Exception {
        Path fetched = file("video.mp4");
        Path first = file("piece1.mp4");
        Path second = file("piece2.mp4");
        Path joined = file("joined.mp4");

        when(downloader.video(any(), any(), any(), any(), any())).thenReturn(fetched);
        when(ffmpeg.cut(any(), any(), eq(1), anyInt(), any())).thenReturn(first);
        when(ffmpeg.cut(any(), any(), eq(2), anyInt(), any())).thenReturn(second);
        when(ffmpeg.concat(eq(List.of(first, second)), any())).thenReturn(joined);

        List<GraphRunner.Output> outputs =
                run("/download " + URL + " /cut 0:00-0:10 0:20-0:30 /join");

        assertEquals(1, outputs.size());
        assertEquals(joined, outputs.getFirst().file());
    }

    private List<GraphRunner.Output> run(String pipeline) throws Exception {
        return runner.run(PipelineText.read(pipeline), work, DownloadProgress.of(f -> {}, f -> {}));
    }

    private Path file(String name) throws Exception {
        Path path = work.resolve(name);
        Files.writeString(path, name);
        return path;
    }
}
