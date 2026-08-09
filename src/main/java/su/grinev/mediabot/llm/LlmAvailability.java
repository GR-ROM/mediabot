package su.grinev.mediabot.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Whether it is worth calling the model at all right now.
 *
 * <p>{@link ModelHttp} already retries inside one call, which is the right answer to a dropped
 * connection. It is the wrong answer to a model host that is simply down: every message then pays
 * three attempts and a timeout each before failing, so a person waits minutes to be told the bot
 * cannot think. This is the layer above that — once the host has failed, the next messages skip it
 * outright and take the deterministic path, until enough time has passed to be worth another try.
 *
 * <p>The backoff doubles and is capped. Capped rather than unbounded because the host coming back is
 * an event nothing tells us about: an ever-growing wait would leave the bot in fallback long after
 * the model was healthy again. A single probe every few minutes is cheap, and the cost of being
 * wrong in this direction is one slow message.
 */
@Component
@Slf4j
public class LlmAvailability {

    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(20);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    /** Consecutive failures before the model is considered down. One may be a hiccup. */
    private static final int FAILURES_BEFORE_OPEN = 2;

    private record State(int consecutiveFailures, Instant openUntil) {}

    private final AtomicReference<State> state =
            new AtomicReference<>(new State(0, Instant.EPOCH));

    /** @return true when the model should be called; false to go straight to the fallback */
    public boolean shouldTry() {
        return Instant.now().isAfter(state.get().openUntil());
    }

    /** How much longer the model is being skipped, for saying so to a person. */
    public Duration cooldownRemaining() {
        Duration left = Duration.between(Instant.now(), state.get().openUntil());
        return left.isNegative() ? Duration.ZERO : left;
    }

    public void succeeded() {
        State previous = state.getAndSet(new State(0, Instant.EPOCH));
        if (previous.consecutiveFailures() > 0) {
            log.info("model host is answering again after {} failure(s)",
                    previous.consecutiveFailures());
        }
    }

    /**
     * Records a failed request and, past the threshold, stops trying for a while.
     *
     * @param cause what went wrong, for the one log line this produces
     */
    public void failed(Exception cause) {
        State updated = state.updateAndGet(current -> {
            int failures = current.consecutiveFailures() + 1;
            if (failures < FAILURES_BEFORE_OPEN) {
                return new State(failures, current.openUntil());
            }
            return new State(failures, Instant.now().plus(backoff(failures)));
        });
        if (updated.consecutiveFailures() >= FAILURES_BEFORE_OPEN) {
            log.warn("model host unreachable ({} consecutive failures) — skipping it for {} s, "
                            + "requests fall back to deterministic routing: {}",
                    updated.consecutiveFailures(), cooldownRemaining().toSeconds(),
                    cause == null ? "" : cause.getMessage());
        } else {
            log.warn("model request failed ({}/{} before backing off): {}",
                    updated.consecutiveFailures(), FAILURES_BEFORE_OPEN,
                    cause == null ? "" : cause.getMessage());
        }
    }

    /**
     * Doubling from the first backoff, capped, jittered.
     *
     * <p>Jittered for the usual reason — several chats failing together must not come back together
     * and hammer a host that is only just up.
     */
    private static Duration backoff(int failures) {
        int doublings = Math.min(failures - FAILURES_BEFORE_OPEN, 8);
        long millis = Math.min(
                FIRST_BACKOFF.toMillis() << doublings,
                MAX_BACKOFF.toMillis());
        long jitter = ThreadLocalRandom.current().nextLong(millis / 4 + 1);
        return Duration.ofMillis(millis - millis / 8 + jitter);
    }
}
