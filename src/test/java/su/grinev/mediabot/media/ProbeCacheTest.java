package su.grinev.mediabot.media;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

/**
 * What makes this worth having is not speed but the shape it allows elsewhere: because a probe is
 * nearly free the second time, a requested height can be checked against what actually exists on
 * every path that takes a job. A guarantee that costs a network round trip is one that gets skipped.
 */
class ProbeCacheTest {

    private static final String URL = "https://www.youtube.com/watch?v=jNQXAC9IVRw";

    private final YtDlp ytDlp = mock(YtDlp.class);
    private final ProbeCache cache = new ProbeCache(ytDlp);

    private static MediaInfo video() {
        return new MediaInfo("Me at the zoo", "jawed", 19, false, List.of(
                new MediaInfo.Format("137", "mp4", 1080, 30, 1_048_576L, "avc1", "none")));
    }

    @Test
    void theSameUrlIsNotProbedTwice() throws Exception {
        when(ytDlp.probe(URL)).thenReturn(video());

        MediaInfo first = cache.probe(URL);
        MediaInfo second = cache.probe(URL);

        verify(ytDlp, times(1)).probe(URL);
        assertEquals(first.title(), second.title());
    }

    @Test
    void twoDifferentUrlsAreTwoProbes() throws Exception {
        when(ytDlp.probe(anyString())).thenReturn(video());

        cache.probe(URL);
        cache.probe(URL + "&other=1");

        verify(ytDlp, times(2)).probe(anyString());
    }

    @Test
    void peekingNeverAsksTheHost() throws Exception {
        when(ytDlp.probe(URL)).thenReturn(video());

        assertTrue(cache.peek(URL).isEmpty(), "nothing has been probed yet");
        verify(ytDlp, times(0)).probe(anyString());

        cache.probe(URL);

        assertEquals("Me at the zoo", cache.peek(URL).orElseThrow().title());
    }

    @Test
    void aProbeThatFailedIsNotRemembered() throws Exception {
        when(ytDlp.probe(URL)).thenThrow(new IOException("the host would not answer"));

        assertThrows(IOException.class, () -> cache.probe(URL));

        // Caching a failure would keep a working video unusable for the whole of the TTL.
        assertFalse(cache.peek(URL).isPresent());
    }

    @Test
    void aFailureIsPassedOnRatherThanTurnedIntoAnEmptyAnswer() throws Exception {
        when(ytDlp.probe(URL)).thenThrow(new IOException("the video is private"));

        IOException raised = assertThrows(IOException.class, () -> cache.probe(URL));

        // An empty MediaInfo reads downstream as "this video has no formats", which is a different
        // problem with a different answer for the person asking.
        assertTrue(raised.getMessage().contains("private"));
    }
}
