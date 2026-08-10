package su.grinev.mediabot.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every one of these ends up in a chat message, which is the whole reason they are formatted here
 * rather than wherever they are printed.
 */
class SizesTest {

    @Test
    void aSizeIsSaidTheWayAPersonWouldSayIt() {
        assertEquals("1 KB", Sizes.bytes(1024));
        assertEquals("50 MB", Sizes.bytes(52_428_800L));
        assertEquals("1.0 GB", Sizes.bytes(1_073_741_824L));
        assertEquals("512 MB", Sizes.bytes(1_073_741_824L / 2));
    }

    @Test
    void aSizeNobodyKnowsIsNotPrintedAsZero() {
        assertEquals("size unknown", Sizes.bytes(0));
        assertEquals("size unknown", Sizes.bytes(-1));
    }

    @Test
    void theDecimalSeparatorDoesNotDependOnWhereThisRuns() {
        // Formatted with the host machine's locale, this would be "1,5 GB" on one server and
        // "1.5 GB" on another, running the same code.
        assertEquals("1.5 GB", Sizes.bytes(1_610_612_736L));
    }

    @Test
    void aLengthIsSaidAsAClock() {
        assertEquals("0:09", Sizes.duration(9));
        assertEquals("1:30", Sizes.duration(90));
        assertEquals("1:00:00", Sizes.duration(3600));
        assertEquals("1:02:05", Sizes.duration(3725));
    }

    @Test
    void aLengthNobodyKnowsIsNotPrintedAsZero() {
        assertEquals("length unknown", Sizes.duration(0));
        assertEquals("length unknown", Sizes.duration(-5));
    }
}
