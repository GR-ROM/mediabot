package su.grinev.mediabot.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure parts: what gets asked of yt-dlp, and what its failures are turned into. */
class YtDlpTest {

    @Test
    void aHeightCeilingFallsBackRatherThanFailing() {
        String selector = YtDlp.videoSelector(720);

        assertTrue(selector.startsWith("bestvideo[height<=720]+bestaudio"));
        // The fallbacks are the point: a host with no height-tagged streams would otherwise answer
        // "requested format is not available" for a video that is sitting right there.
        assertTrue(selector.endsWith("/bestvideo+bestaudio/best"),
                "there has to be a path that works when nothing is tagged: " + selector);
    }

    @Test
    void noCeilingAsksForTheBest() {
        assertEquals("bestvideo+bestaudio/best", YtDlp.videoSelector(null));
    }

    @Test
    void theFailuresWorthNamingAreNamed() {
        assertTrue(YtDlp.explain("ERROR: Private video. Sign in if you've been granted access")
                .contains("private"));
        assertTrue(YtDlp.explain("ERROR: Video unavailable. This video has been removed")
                .contains("removed"));
        assertTrue(YtDlp.explain("ERROR: The uploader has not made this video available in your "
                + "country").contains("blocked"));
        assertTrue(YtDlp.explain("ERROR: Sign in to confirm you're not a bot")
                .contains("cookies"));
        assertTrue(YtDlp.explain("ERROR: Requested format is not available")
                .contains("quality"));
    }

    @Test
    void anUnrecognisedFailureIsNotRepeatedAtThePerson() {
        String stderr = """
                Traceback (most recent call last):
                  File "yt_dlp/YoutubeDL.py", line 1234, in wrapper
                WARNING: falling back
                ERROR: unable to download webpage: <urlopen error timed out>""";

        String explained = YtDlp.explain(stderr);

        // This used to be the last line of stderr, handed to whoever sent the link. A traceback and
        // a URL presented as an answer reads as the bot being broken rather than the video being
        // unavailable — and the whole of it is in the log either way.
        assertEquals(YtDlp.UNRECOGNISED, explained);
        assertFalse(explained.contains("urlopen"), explained);
        assertFalse(explained.contains("Traceback"), explained);
        assertTrue(explained.length() < 80, "short enough to read in a chat: " + explained);
    }

    @Test
    void anEmptyFailureStillSaysSomething() {
        assertFalse(YtDlp.explain("").isBlank(),
                "a blank explanation reads to the user as the bot having nothing to say");
    }

    @Test
    void theBotCheckIsNamedOnceSoItCanBeRecognisedElsewhere() {
        // OwnerAlerts matches on this to decide whether a failure is the owner's to fix. Matched on
        // the constant rather than on the sentence, so the two cannot drift apart.
        assertEquals(YtDlp.NEEDS_AUTHENTICATION,
                YtDlp.explain("ERROR: Sign in to confirm you're not a bot"));
        assertNotEquals(YtDlp.NEEDS_AUTHENTICATION,
                YtDlp.explain("ERROR: Private video. Sign in if you've been granted access"));
    }
}
