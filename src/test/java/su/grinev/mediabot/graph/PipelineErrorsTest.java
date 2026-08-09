package su.grinev.mediabot.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a refusal says, which is the reason the steps are spelled out rather than described.
 *
 * <p>Every message has to carry three things: what was wrong, the word it was wrong about, and what
 * the step would have taken instead. A refusal that only manages the first leaves somebody guessing,
 * and guessing is what the strict form exists to avoid.
 */
class PipelineErrorsTest {

    private static final String URL = "https://www.youtube.com/watch?v=jNQXAC9IVRw";

    @Test
    void aTimecodeThatIsNotOneSaysSoAndShowsTheForm() {
        String message = refusalOf("/download " + URL + " /cut 1:0x-2:00");

        assertTrue(message.contains("1:0x"), message);
        assertTrue(message.contains("1:30"), message);
        assertTrue(message.contains("/cut 1:00-2:00"), message);
    }

    @Test
    void oneTimecodeIsNotARange() {
        String message = refusalOf("/download " + URL + " /cut 1:00");

        assertTrue(message.contains("one timecode, not a range"), message);
        assertTrue(message.contains("1:00-2:00"), message);
    }

    @Test
    void aRangeThatRunsBackwardsNamesBothEnds() {
        String message = refusalOf("/download " + URL + " /cut 2:00-1:00");

        assertTrue(message.contains("1:00"), message);
        assertTrue(message.contains("2:00"), message);
        assertTrue(message.contains("before"), message);
    }

    @Test
    void anEmptyRangeIsRefusedRatherThanCutToNothing() {
        String message = refusalOf("/download " + URL + " /cut 1:00-1:00");

        assertTrue(message.contains("empty"), message);
    }

    @Test
    void tooManyPartsInATimecodeIsItsOwnComplaint() {
        String message = refusalOf("/download " + URL + " /cut 1:2:3:4-5:00");

        assertTrue(message.contains("too many parts"), message);
        assertTrue(message.contains("h:mm:ss"), message);
    }

    @Test
    void aWordAStepDoesNotTakeIsQuotedWithWhatItDoesTake() {
        String message = refusalOf("/download " + URL + " /encode mp4 720p sharpen");

        assertTrue(message.contains("sharpen"), message);
        assertTrue(message.contains("/encode takes"), message);
        assertTrue(message.contains("mkv"), message);
    }

    @Test
    void aStepWithNoArgumentsSaysItTakesNothing() {
        String message = refusalOf("/download " + URL + " /normalize loudly");

        assertTrue(message.contains("loudly"), message);
        assertTrue(message.contains("/normalize takes nothing"), message);
    }

    @Test
    void anUnknownStepListsTheOnesThatExist() {
        String message = refusalOf("/download " + URL + " /deblur");

        assertTrue(message.contains("/deblur"), message);
        assertTrue(message.contains("/cut"), message);
        assertTrue(message.contains("/normalize"), message);
        assertTrue(message.contains("/join"), message);
    }

    @Test
    void aCutWithNoRangeSaysWhatARangeLooksLike() {
        String message = refusalOf("/download " + URL + " /cut");

        assertTrue(message.contains("at least one range"), message);
        assertTrue(message.contains("1:00-2:00"), message);
    }

    @Test
    void startingWithoutSomethingToWorkOnPointsAtDownload() {
        String message = refusalOf("/cut 1:00-2:00 /encode 480p");

        assertTrue(message.contains("/download"), message);
    }

    @Test
    void joiningOnePieceExplainsWhyThereIsNothingToDo() {
        String message = refusalOf("/download " + URL + " /join");

        assertTrue(message.contains("nothing to join"), message);
    }

    private static String refusalOf(String pipeline) {
        return assertThrows(Pipeline.Invalid.class, () -> PipelineText.read(pipeline)).getMessage();
    }
}
