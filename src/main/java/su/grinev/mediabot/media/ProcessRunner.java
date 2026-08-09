package su.grinev.mediabot.media;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs an external binary and collects what it said.
 *
 * <p>From yt-downloader, with a timeout added. The line splitting is the part worth keeping: ffmpeg
 * writes its progress with a carriage return and no newline, so a plain {@code readLine} on its
 * stderr blocks until the process ends and delivers the whole progress history as one line —
 * i.e. no progress at all.
 *
 * <p>Every command is a {@code List<String>}, never a string to be split by a shell. With the URL
 * coming from a chat message and the arguments around it chosen by a model, a shell in this path
 * would be the whole security story.
 */
@UtilityClass
@Slf4j
public class ProcessRunner {

    public record Result(int exitCode, List<String> stdout, List<String> stderr) {
        public String stderrText() {
            return String.join("\n", stderr);
        }
        public String stdoutText() {
            return String.join("", stdout);
        }
    }

    /** A command that ran longer than it was given. */
    public static class TimedOut extends IOException {
        public TimedOut(String message) {
            super(message);
        }
    }

    /**
     * @param command the binary and its arguments, already separated
     * @param timeout how long it may run before being killed; null or zero means no limit
     * @param lineCallback called for every line of both streams as it arrives, or null
     * @throws TimedOut when the timeout expired, after the process has been killed
     */
    public Result run(List<String> command, Duration timeout, Consumer<String> lineCallback) throws IOException, InterruptedException {

        log.debug("running: {}", String.join(" ", command));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        Process process = pb.start();
        process.getOutputStream().close();   // nothing to say to it; do not leave it waiting

        List<String> stdoutLines = new ArrayList<>();
        List<String> stderrLines = new ArrayList<>();

        // Both streams are drained concurrently. A process whose stderr pipe fills up while we read
        // stdout blocks forever, which looks exactly like a slow download.
        Thread stderrThread = Thread.ofVirtual().start(() -> {
            try {
                readLines(process.getErrorStream(), stderrLines, lineCallback);
            } catch (IOException e) {
                log.debug("stderr closed early: {}", e.toString());
            }
        });
        Thread stdoutThread = Thread.ofVirtual().start(() -> {
            try (var reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdoutLines.add(line);
                    if (lineCallback != null) {
                        lineCallback.accept(line);
                    }
                }
            } catch (IOException e) {
                log.debug("stdout closed early: {}", e.toString());
            }
        });

        boolean finished;
        try {
            finished = timeout == null || timeout.isZero() || timeout.isNegative()
                    ? waitForever(process)
                    : process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // Cancelling the task that is waiting does nothing to the process it started: without
            // this, a cancelled download keeps downloading, invisible and unattributable, until it
            // finishes on its own.
            process.destroyForcibly();
            stdoutThread.join();
            stderrThread.join();
            throw e;
        }

        if (!finished) {
            process.destroyForcibly();
            // Joined even so: the threads are appending to lists this method returns.
            stdoutThread.join();
            stderrThread.join();
            throw new TimedOut(command.get(0) + " did not finish within " + timeout.toSeconds()
                    + " s and was killed");
        }
        stdoutThread.join();
        stderrThread.join();
        return new Result(process.exitValue(), stdoutLines, stderrLines);
    }

    public Result run(List<String> command, Duration timeout)
            throws IOException, InterruptedException {
        return run(command, timeout, null);
    }

    private boolean waitForever(Process process) throws InterruptedException {
        process.waitFor();
        return true;
    }

    /**
     * Reads a stream, splitting on {@code \n}, {@code \r} or {@code \r\n}.
     *
     * <p>The carriage-return case is the whole reason this is not a {@code BufferedReader}: it is how
     * ffmpeg and yt-dlp redraw a progress line in place.
     */
    private void readLines(InputStream rawIs, List<String> lines, Consumer<String> callback)
            throws IOException {
        BufferedInputStream is = new BufferedInputStream(rawIs);
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = is.read()) != -1) {
            if (ch == '\r' || ch == '\n') {
                if (!sb.isEmpty()) {
                    String line = sb.toString();
                    lines.add(line);
                    if (callback != null) {
                        callback.accept(line);
                    }
                    sb.setLength(0);
                }
                if (ch == '\r') {
                    // Treat \r\n as one separator rather than as an empty line.
                    is.mark(1);
                    int next = is.read();
                    if (next != '\n' && next != -1) {
                        is.reset();
                    }
                }
            } else {
                sb.append((char) ch);
            }
        }
        if (!sb.isEmpty()) {
            String line = sb.toString();
            lines.add(line);
            if (callback != null) {
                callback.accept(line);
            }
        }
    }
}
