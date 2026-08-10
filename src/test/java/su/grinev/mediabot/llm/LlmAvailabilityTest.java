package su.grinev.mediabot.llm;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether it is worth calling the model at all right now.
 *
 * <p>The retry inside one call is the right answer to a dropped connection and the wrong one to a
 * host that is simply down: every message then pays three attempts and a timeout each before
 * failing, so a person waits minutes to be told the bot cannot think.
 */
class LlmAvailabilityTest {

    private final LlmAvailability availability = new LlmAvailability();

    @Test
    void aHealthyHostIsAlwaysWorthTrying() {
        assertTrue(availability.shouldTry());
        assertEquals(Duration.ZERO, availability.cooldownRemaining());
    }

    @Test
    void oneFailureIsAHiccupAndNotAnOutage() {
        availability.failed(new IOException("connection reset"));

        assertTrue(availability.shouldTry(),
                "a single dropped connection is what the in-call retry is for");
    }

    @Test
    void twoFailuresStopTheCalling() {
        availability.failed(new IOException("connection refused"));
        availability.failed(new IOException("connection refused"));

        assertFalse(availability.shouldTry());
        assertTrue(availability.cooldownRemaining().toSeconds() > 0,
                "and it can say how much longer, so a person can be told");
    }

    @Test
    void theWaitIsCappedRatherThanGrowingForever() {
        for (int i = 0; i < 40; i++) {
            availability.failed(new IOException("still down"));
        }

        // The host coming back is an event nothing tells us about, so an ever-growing wait would
        // leave the bot in fallback long after the model was healthy again.
        assertTrue(availability.cooldownRemaining().toMinutes() <= 5,
                "got " + availability.cooldownRemaining());
    }

    @Test
    void oneGoodAnswerClearsTheFailures() {
        availability.failed(new IOException("down"));
        availability.failed(new IOException("down"));

        availability.succeeded();

        assertTrue(availability.shouldTry());
        assertEquals(Duration.ZERO, availability.cooldownRemaining());
    }

    @Test
    void aFailureWithNothingToSayIsStillRecorded() {
        availability.failed(null);
        availability.failed(null);

        assertFalse(availability.shouldTry(),
                "a null cause is a logging problem, not a reason to keep calling a dead host");
    }
}
