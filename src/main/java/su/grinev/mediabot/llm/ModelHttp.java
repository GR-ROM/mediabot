package su.grinev.mediabot.llm;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/** One call to a model server, retried the way both clients need it. */
@Slf4j
public class ModelHttp {

    private static final int ATTEMPTS = 3;
    private static final Duration BASE_DELAY = Duration.ofMillis(1_500);

    /** A server asking us back in an hour is telling us to give up, not to sleep for an hour. */
    private static final Duration MAX_HONOURED_RETRY_AFTER = Duration.ofSeconds(60);

    private final HttpClient http;
    private final String what;

    /**
     * @param http the client to send with; its timeouts are the caller's business
     * @param what what to call the far end in an error message
     */
    public ModelHttp(HttpClient http, String what) {
        this.http = http;
        this.what = what;
    }

    /**
     * @param request the call to make
     * @return the successful response body
     * @throws IOException when the server refused, or would not answer after every attempt
     * @throws InterruptedException if the wait between attempts is interrupted
     */
    public String send(HttpRequest request) throws IOException, InterruptedException {
        IOException last = null;
        Duration perAttempt = request.timeout().orElse(null);

        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            Duration wait = BASE_DELAY.multipliedBy(attempt);
            long attemptStarted = System.nanoTime();
            try {
                HttpResponse<String> response = http.send(request,
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return response.body();
                }
                if (isPermanent(response.statusCode())) {
                    throw new IOException(what + " rejected the request ("
                            + response.statusCode() + "): " + response.body());
                }
                last = new IOException(what + " error " + response.statusCode()
                        + ": " + response.body());
                wait = retryAfter(response).orElse(wait);
            } catch (IOException e) {
                if (isPermanent(e)) {
                    throw e;
                }
                last = new IOException("cannot reach the " + what + " — " + describe(e), e);
            }

            if (attempt == ATTEMPTS) {
                break;   // no point sleeping on the way out
            }
            if (wasExpensive(attemptStarted, perAttempt)) {
                log.warn("{} attempt {}/{} spent most of its {} s timeout before failing; not "
                                + "trying again, since another attempt would cost as much: {}",
                        what, attempt, ATTEMPTS, perAttempt.toSeconds(), last.getMessage());
                break;
            }
            log.warn("{} attempt {}/{} failed, retrying in {} ms: {}",
                    what, attempt, ATTEMPTS, wait.toMillis(), last.getMessage());
            Thread.sleep(jittered(wait));
        }
        throw last;
    }

    /**
     * Whether this failure cost enough that repeating it is not worth it.
     *
     * <p>What the retries are for is a dropped connection, and that fails in milliseconds — three
     * attempts at one of those is cheap and has saved whole vision passes. A server that hangs
     * until the timeout is the opposite: retrying multiplies the most expensive thing in the
     * system. With a 900-second vision timeout the old policy was three quarters of an hour inside
     * a single tool step, against a tool budget of two minutes, with nothing on the console.
     *
     * <p>Bounded by the timeout on the request itself rather than by a setting of its own, so the
     * ceiling follows whatever the caller already decided one call may cost.
     *
     * @param perAttempt the request's own timeout, or null when it has none
     * @return true when this attempt used up half its allowance or more
     */
    private static boolean wasExpensive(long attemptStarted, Duration perAttempt) {
        if (perAttempt == null || perAttempt.isZero() || perAttempt.isNegative()) {
            return false;
        }
        long spentMillis = (System.nanoTime() - attemptStarted) / 1_000_000;
        return spentMillis * 2 >= perAttempt.toMillis();
    }

    /** 4xx is a bug in what we sent; 429 is the server asking for patience, not refusing. */
    private static boolean isPermanent(int status) {
        return status >= 400 && status < 500 && status != 429;
    }

    /** Our own refusal, thrown from inside the try, must not be caught and retried by it. */
    private static boolean isPermanent(IOException e) {
        return e.getMessage() != null && e.getMessage().contains("rejected the request");
    }

    /** So that callers which failed together do not come back together. */
    private static long jittered(Duration wait) {
        long millis = wait.toMillis();
        return millis / 2 + ThreadLocalRandom.current().nextLong(millis / 2 + 1);
    }

    private static Optional<Duration> retryAfter(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After")
                .map(value -> {
                    try {
                        return Duration.ofSeconds(Long.parseLong(value.strip()));
                    } catch (NumberFormatException e) {
                        return null;   // an HTTP-date, more precision than this needs
                    }
                })
                .filter(d -> d != null && !d.isNegative()
                        && d.compareTo(MAX_HONOURED_RETRY_AFTER) <= 0);
    }

    /** ConnectException carries no message, and "— null" tells the user nothing. */
    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
