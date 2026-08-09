package su.grinev.mediabot.console;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What reaches a terminal.
 *
 * <p>The model writes curly quotes, narrow no-break spaces and non-breaking hyphens, and the
 * progress line is drawn with block glyphs. On a Windows console none of that is the codepage the
 * JVM writes in, so it arrives as ΓÇ£ and ╨á — several lines of it per download.
 */
class ConsoleTextTest {

    @Test
    void theModelsTypographyBecomesTypeableCharacters() {
        assertEquals("\"sample-5s\" - 81 MB, 720 p",
                ConsoleText.plain("“sample‑5s” – 81 MB, 720 p"));
    }

    @Test
    void theProgressBarAndItsIconsSurviveAsAscii() {
        assertEquals("###....... 30% A video", ConsoleText.plain("▰▰▰▱▱▱▱▱▱▱ 30% A video"));
        assertEquals("Downloading A video", ConsoleText.plain("⏳ Downloading A video"));
        assertEquals("ok A video (12 MB)", ConsoleText.plain("✅ A video (12 MB)"));
    }

    @Test
    void aRunOfEmptyLinesIsNotAScreenful() {
        assertEquals("one\n\ntwo", ConsoleText.plain("one\n\n\n\n   \ntwo\n\n"));
    }

    @Test
    void aTitleTooLongForOneLineIsCut() {
        String cut = ConsoleText.shorten("A very long video title that nobody reads to the end of", 20);

        assertEquals(20, cut.length());
        assertTrue(cut.endsWith("..."));
    }
}
