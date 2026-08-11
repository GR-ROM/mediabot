package su.grinev.mediabot.media;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import su.grinev.mediabot.AgentProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Re-encoding, for the cases yt-dlp's format selection cannot cover.
 *
 * <p>Used sparingly on purpose. A host that publishes several heights should be asked for the right
 * one — that costs no CPU and loses nothing. This is for a host that publishes exactly one stream,
 * where the only way to a smaller file is to encode it again, and for the file that came back too
 * big to send.
 */
@Component
@Slf4j
public class Ffmpeg {

    /** {@code out_time_ms=12345678} in the -progress stream. Microseconds, despite the name. */
    /**
     * Scales so that the short side becomes {@code height}, whichever side that is.
     *
     * <p>"720p" is a claim about the short side. Scaling the height instead is right for a
     * landscape video and wrong for every phone recording: a 1080x1920 reel asked for at 720p came
     * out 406x720, a third of the pixels, which arrives looking like a bad bitrate rather than like
     * the wrong size. The naming already knew this — a vertical video is called 1080p, not 1920p —
     * and the scaling did not.
     *
     * <p>-2 rather than -1 on the free side: H.264 wants even dimensions, and an odd source
     * produces one otherwise.
     */
    static String scaleShortSideTo(int height) {
        // The commas are escaped because a comma separates filters in a filtergraph, so an
        // unescaped one inside if() ends the scale filter halfway through an expression. ffmpeg
        // answers that with "Error opening output files: Invalid argument", which names neither the
        // filter nor the comma.
        String landscape = "gt(iw\\,ih)";
        return "scale=w=if(%s\\,-2\\,%d):h=if(%s\\,%d\\,-2)"
                .formatted(landscape, height, landscape, height);
    }

    private static final Pattern OUT_TIME = Pattern.compile("out_time_ms=(\\d+)");

    private final Path binary;
    private final Duration timeout;

    public Ffmpeg(AgentProperties props) {
        this.binary = props.media().ffmpeg();
        // Encoding is slower than downloading, and shares the same ceiling for lack of a better one.
        this.timeout = Duration.ofSeconds(props.media().downloadTimeoutSeconds());
    }

    public boolean available() {
        return binary != null && Files.isRegularFile(binary);
    }

    public Path merge(Path video, Path audio, Path target, int durationSeconds,
                      DoubleConsumer progress) throws IOException, InterruptedException {

        if (!available()) {
            throw new IOException("ffmpeg is not configured (mediabot.media.ffmpeg), and without it "
                    + "the video and audio streams cannot be joined");
        }
        try {
            return mux(video, audio, target, durationSeconds, progress, copyableAudio(audio));
        } catch (IOException e) {
            if (!copyableAudio(audio)) {
                throw e;
            }
            log.warn("copying the audio track into the mp4 failed ({}), re-encoding it instead",
                    e.getMessage());
            return mux(video, audio, target, durationSeconds, progress, false);
        }
    }

    private Path mux(Path video, Path audio, Path target, int durationSeconds,
                     DoubleConsumer progress, boolean copyAudio)
            throws IOException, InterruptedException {

        List<String> command = new ArrayList<>(List.of(
                binary.toString(),
                "-hide_banner",
                "-nostdin",
                "-y",
                "-i", video.toString(),
                "-i", audio.toString(),
                "-map", "0:v:0",
                "-map", "1:a:0",
                "-c:v", "copy"));
        command.addAll(copyAudio
                ? List.of("-c:a", "copy")
                : List.of("-c:a", "aac", "-b:a", "192k"));
        command.addAll(List.of(
                "-movflags", "+faststart",
                "-progress", "pipe:1",
                "-loglevel", "error",
                target.toString()));

        var result = ProcessRunner.run(command, timeout,
                line -> reportProgress(line, durationSeconds, progress));
        if (result.exitCode() != 0) {
            Files.deleteIfExists(target);
            throw new IOException("ffmpeg could not join the video and audio streams: "
                    + lastLine(result.stderrText()));
        }
        if (!Files.isRegularFile(target) || Files.size(target) == 0) {
            Files.deleteIfExists(target);
            throw new IOException("ffmpeg finished but produced no file");
        }
        log.info("merged {} + {} into {} ({} bytes)", video.getFileName(), audio.getFileName(),
                target.getFileName(), Files.size(target));
        return target;
    }

    private static boolean copyableAudio(Path audio) {
        String name = audio.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".m4a") || name.endsWith(".mp4") || name.endsWith(".aac");
    }


    public Path encode(Path source, Integer height, Container container, Quality quality,
                       int durationSeconds, DoubleConsumer progress)
            throws IOException, InterruptedException {

        if (!available()) {
            throw new IOException("ffmpeg is not configured (mediabot.media.ffmpeg), and without "
                    + "it there is nothing to re-encode with");
        }
        String extension = container.extension();
        String name = height == null
                ? baseName(source) + " (re-encoded)." + extension
                : scaledName(baseName(source), height).replaceAll("\\.[^.]+$", "." + extension);
        Path target = uniqueSibling(source, name);
        if (target.equals(source)) {
            throw new IOException("re-encoding " + source.getFileName()
                    + " would write over the file being read");
        }

        List<String> command = new ArrayList<>(List.of(
                binary.toString(),
                "-hide_banner",
                "-nostdin",
                "-y",
                "-i", source.toString()));
        if (height != null) {
            command.addAll(List.of("-vf", scaleShortSideTo(height)));
        }
        command.addAll(codecsFor(container, quality));
        if (container.wantsFaststart()) {
            // Puts the index at the front so the file can start playing before it is fully
            // downloaded — which is the difference between a video in Telegram and a file.
            command.addAll(List.of("-movflags", "+faststart"));
        }
        command.addAll(List.of("-progress", "pipe:1", "-loglevel", "error", target.toString()));

        var result = ProcessRunner.run(command, timeout,
                line -> reportProgress(line, durationSeconds, progress));
        if (result.exitCode() != 0) {
            Files.deleteIfExists(target);
            throw new IOException("ffmpeg could not re-encode it: " + lastLine(result.stderrText()));
        }
        if (!Files.isRegularFile(target) || Files.size(target) == 0) {
            throw new IOException("ffmpeg finished but produced no file");
        }
        log.info("transcoded {} to {} {}: {} -> {} bytes", source.getFileName(),
                height == null ? extension : height + "p " + extension,
                quality.name().toLowerCase(java.util.Locale.ROOT),
                Files.size(source), Files.size(target));
        return target;
    }

    /**
     * Keeps {@code window} out of {@code source}, copying the streams rather than re-encoding.
     *
     * <p>{@code -ss} goes before {@code -i} so the seek happens while reading instead of by
     * decoding and throwing frames away, and the length is given as {@code -t} rather than
     * {@code -to} because {@code -to} paired with an input seek is counted from the input timeline
     * on some builds and the output timeline on others. {@code -avoid_negative_ts make_zero}
     * rewrites the timestamps the copied packets carry from the middle of the file, without which a
     * player shows the original duration with a hole at the front.
     *
     * <p>Copying cuts on keyframes, so the start can slip back by up to one GOP. That is the price
     * of a step that costs no CPU, and it is the right trade for one that runs before the encode.
     */
    public Path cut(Path source, Trim window, int piece, int durationSeconds,
                    DoubleConsumer progress) throws IOException, InterruptedException {

        if (!available()) {
            throw new IOException("ffmpeg is not configured (mediabot.media.ffmpeg), and without "
                    + "it there is nothing to cut with");
        }
        String extension = extensionOf(source);
        Path target = uniqueSibling(source, baseName(source) + " (piece " + piece + ")." + extension);

        List<String> command = new ArrayList<>(List.of(
                binary.toString(),
                "-hide_banner",
                "-nostdin",
                "-y",
                "-ss", String.valueOf(window.startSeconds()),
                "-i", source.toString()));
        if (window.durationSeconds() != null) {
            command.addAll(List.of("-t", String.valueOf(window.durationSeconds())));
        }
        command.addAll(List.of("-c", "copy", "-avoid_negative_ts", "make_zero"));
        if ("mp4".equals(extension) || "mov".equals(extension)) {
            command.addAll(List.of("-movflags", "+faststart"));
        }
        command.addAll(List.of("-progress", "pipe:1", "-loglevel", "error", target.toString()));

        Integer kept = window.durationSeconds();
        var result = ProcessRunner.run(command, timeout,
                line -> reportProgress(line, kept == null ? durationSeconds : kept, progress));
        if (result.exitCode() != 0) {
            Files.deleteIfExists(target);
            throw new IOException("ffmpeg could not cut it: " + lastLine(result.stderrText()));
        }
        if (!Files.isRegularFile(target) || Files.size(target) == 0) {
            throw new IOException("ffmpeg finished but produced no piece");
        }
        log.info("cut {} to {}: {} bytes", source.getFileName(), window.describe(),
                Files.size(target));
        return target;
    }

    /**
     * Keeps {@code window} and re-encodes what is left, in one pass.
     *
     * <p>Worth its own method because the pair is better than the two steps run in order, not merely
     * cheaper. A copying cut can only start on a keyframe, so it hands the encoder up to a GOP of
     * material nobody asked for — measured at three seconds on a real reel — and the encoder then
     * spends real CPU on those seconds. Re-encoding from an input seek is exact to the frame, which
     * makes this both the correct answer and the faster one.
     */
    public Path cutAndEncode(Path source, Trim window, Integer height, Container container,
                             Quality quality, int piece, DoubleConsumer progress)
            throws IOException, InterruptedException {

        if (!available()) {
            throw new IOException("ffmpeg is not configured (mediabot.media.ffmpeg), and without "
                    + "it there is nothing to cut with");
        }
        String extension = container.extension();
        Path target = uniqueSibling(source,
                baseName(source) + " (piece " + piece + ")." + extension);

        List<String> command = new ArrayList<>(List.of(
                binary.toString(),
                "-hide_banner", "-nostdin", "-y",
                "-ss", String.valueOf(window.startSeconds()),
                "-i", source.toString()));
        if (window.durationSeconds() != null) {
            command.addAll(List.of("-t", String.valueOf(window.durationSeconds())));
        }
        if (height != null) {
            command.addAll(List.of("-vf", scaleShortSideTo(height)));
        }
        command.addAll(codecsFor(container, quality));
        if (container.wantsFaststart()) {
            command.addAll(List.of("-movflags", "+faststart"));
        }
        command.addAll(List.of("-progress", "pipe:1", "-loglevel", "error", target.toString()));

        Integer kept = window.durationSeconds();
        var result = ProcessRunner.run(command, timeout,
                line -> reportProgress(line, kept == null ? 0 : kept, progress));
        if (result.exitCode() != 0) {
            Files.deleteIfExists(target);
            throw new IOException("ffmpeg could not cut and re-encode it: "
                    + lastLine(result.stderrText()));
        }
        if (!Files.isRegularFile(target) || Files.size(target) == 0) {
            throw new IOException("ffmpeg finished but produced no piece");
        }
        log.info("cut {} to {} and encoded it to {}: {} bytes", source.getFileName(),
                window.describe(), height == null ? extension : height + "p " + extension,
                Files.size(target));
        return target;
    }

    private static List<String> codecsFor(Container container, Quality quality) {
        List<String> codecs = new ArrayList<>(container.videoCodec());
        codecs.addAll(List.of("-crf", String.valueOf(quality.crf(container))));
        codecs.addAll(container.audioCodec());
        return codecs;
    }

    /**
     * Brings the loudness to a broadcast target, leaving the picture alone.
     *
     * <p>One pass rather than the two loudnorm can do: the second pass buys accuracy measured in
     * tenths of a decibel, at the cost of decoding the whole file twice, and nobody comparing two
     * clips in a chat can hear the difference.
     */
    public Path normalizeLoudness(Path source, int durationSeconds, DoubleConsumer progress)
            throws IOException, InterruptedException {

        if (!available()) {
            throw new IOException("ffmpeg is not configured (mediabot.media.ffmpeg), and without "
                    + "it there is nothing to normalize with");
        }
        String extension = extensionOf(source);
        Path target = uniqueSibling(source, baseName(source) + " (normalized)." + extension);

        List<String> command = new ArrayList<>(List.of(
                binary.toString(),
                "-hide_banner", "-nostdin", "-y",
                "-i", source.toString(),
                "-af", "loudnorm=I=-16:TP=-1.5:LRA=11",
                "-c:v", "copy"));
        command.addAll(Container.ofExtension(extension).audioCodec());
        if (Container.ofExtension(extension).wantsFaststart()) {
            command.addAll(List.of("-movflags", "+faststart"));
        }
        command.addAll(List.of("-progress", "pipe:1", "-loglevel", "error", target.toString()));

        var result = ProcessRunner.run(command, timeout,
                line -> reportProgress(line, durationSeconds, progress));
        if (result.exitCode() != 0) {
            Files.deleteIfExists(target);
            throw new IOException("ffmpeg could not normalize it: " + lastLine(result.stderrText()));
        }
        log.info("normalized {}", source.getFileName());
        return target;
    }

    public Path extractAudio(Path source, AudioFormat format, int durationSeconds,
                             DoubleConsumer progress) throws IOException, InterruptedException {

        if (!available()) {
            throw new IOException("ffmpeg is not configured (mediabot.media.ffmpeg), and without "
                    + "it there is nothing to take the audio out with");
        }
        Path target = uniqueSibling(source, baseName(source) + "." + format.extension());

        List<String> codec = format.codec();
        List<String> command = new ArrayList<>(List.of(
                binary.toString(),
                "-hide_banner", "-nostdin", "-y",
                "-i", source.toString(),
                "-vn"));
        command.addAll(codec);
        command.addAll(List.of("-progress", "pipe:1", "-loglevel", "error", target.toString()));

        var result = ProcessRunner.run(command, timeout,
                line -> reportProgress(line, durationSeconds, progress));
        if (result.exitCode() != 0) {
            Files.deleteIfExists(target);
            throw new IOException("ffmpeg could not take the audio out: "
                    + lastLine(result.stderrText()));
        }
        return target;
    }

    /**
     * Joins pieces that came from one source, which is the only case this is reached for, and so
     * the only case worth supporting: the concat demuxer copies packets and requires every input to
     * share codecs and timebase, and pieces cut from one file always do.
     */
    public Path concat(List<Path> pieces, DoubleConsumer progress)
            throws IOException, InterruptedException {

        if (!available()) {
            throw new IOException("ffmpeg is not configured (mediabot.media.ffmpeg), and without "
                    + "it there is nothing to join with");
        }
        if (pieces.size() < 2) {
            throw new IOException("joining needs at least two pieces");
        }
        Path first = pieces.getFirst();
        String extension = extensionOf(first);
        Path listing = first.resolveSibling("concat-" + System.nanoTime() + ".txt");
        Path target = uniqueSibling(first, baseName(first) + " (joined)." + extension);

        StringBuilder list = new StringBuilder();
        for (Path piece : pieces) {
            list.append("file '").append(piece.toAbsolutePath().toString().replace("'", "'\\''"))
                    .append("'").append(System.lineSeparator());
        }
        Files.writeString(listing, list.toString());

        try {
            List<String> command = new ArrayList<>(List.of(
                    binary.toString(),
                    "-hide_banner", "-nostdin", "-y",
                    "-f", "concat", "-safe", "0",
                    "-i", listing.toString(),
                    "-c", "copy"));
            if (Container.ofExtension(extension).wantsFaststart()) {
                command.addAll(List.of("-movflags", "+faststart"));
            }
            command.addAll(List.of("-progress", "pipe:1", "-loglevel", "error", target.toString()));

            var result = ProcessRunner.run(command, timeout, line -> reportProgress(line, 0, progress));
            if (result.exitCode() != 0) {
                Files.deleteIfExists(target);
                throw new IOException("ffmpeg could not join the pieces: "
                        + lastLine(result.stderrText()));
            }
            log.info("joined {} pieces into {}", pieces.size(), target.getFileName());
            return target;
        } finally {
            Files.deleteIfExists(listing);
        }
    }

    private static Path uniqueSibling(Path source, String name) {
        Path candidate = source.resolveSibling(name);
        for (int counter = 1; Files.exists(candidate); counter++) {
            int dot = name.lastIndexOf('.');
            candidate = source.resolveSibling(
                    name.substring(0, dot) + " " + counter + name.substring(dot));
        }
        return candidate;
    }

    private static String extensionOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "mp4";
    }


    private static void reportProgress(String line, int durationSeconds, DoubleConsumer progress) {
        if (progress == null || durationSeconds <= 0) {
            return;
        }
        Matcher m = OUT_TIME.matcher(line);
        if (m.find()) {
            double seconds = Long.parseLong(m.group(1)) / 1_000_000.0;
            progress.accept(Math.min(1.0, seconds / durationSeconds));
        }
    }

    static String scaledName(String base, int height) {
        return base.replaceFirst("\\s*\\(\\d{2,4}p\\)$", "") + "_" + height + "p.mp4";
    }

    private static String baseName(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private static String lastLine(String text) {
        String[] lines = text.strip().split("\n");
        String last = lines.length == 0 ? "" : lines[lines.length - 1].strip();
        return last.isBlank() ? "with no explanation" : last;
    }
}
