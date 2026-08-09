package su.grinev.mediabot.media;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.function.DoubleConsumer;

@Component
@RequiredArgsConstructor
@Slf4j
public class VideoDownloader {

    private static final double VIDEO_SHARE = 0.8;
    private static final int MAX_NAME_CHARS = 100;

    private final YtDlp ytDlp;
    private final Ffmpeg ffmpeg;
    private final ProbeCache probes;

    public Path video(String url, Integer maxHeight, Path into, DownloadProgress progress)
            throws IOException, InterruptedException {
        return video(url, maxHeight, into, progress, MediaInfo.Purpose.DELIVERY);
    }

    public Path video(String url, Integer maxHeight, Path into, DownloadProgress progress,
                      MediaInfo.Purpose purpose) throws IOException, InterruptedException {

        Optional<MediaInfo> probed = probe(url);
        if (probed.isEmpty()) {
            return ytDlp.download(url, maxHeight, into, progress::downloading);
        }
        MediaInfo info = probed.get();
        // For a re-encode the number means the height about to be produced, not a ceiling on what
        // to fetch — so it picks the cheapest source that can still produce it rather than the
        // tallest one under it.
        Integer ceiling = purpose == MediaInfo.Purpose.RE_ENCODING && maxHeight != null
                ? info.sourceHeightFor(maxHeight)
                : maxHeight;
        if (!java.util.Objects.equals(ceiling, maxHeight)) {
            log.info("job files: re-encoding to {}p, so fetching {}p rather than the tallest {}p",
                    maxHeight, ceiling, info.maxHeight());
        }
        Optional<MediaInfo.Format> video = info.pickVideo(ceiling, purpose);
        Optional<MediaInfo.Format> audio = info.pickAudio();

        if (video.isPresent() && audio.isPresent() && ffmpeg.available()) {
            return merged(url, info, video.get(), audio.get(), into, progress);
        }
        if (!ffmpeg.available()) {
            log.warn("no ffmpeg configured (mediabot.media.ffmpeg), so separate video and audio "
                    + "streams cannot be joined — falling back to whatever yt-dlp can take whole");
        }
        Optional<MediaInfo.Format> single = info.pickProgressive(ceiling, purpose);
        if (single.isPresent()) {
            return single(url, info, single.get(), into, progress);
        }
        return ytDlp.download(url, maxHeight, into, progress::downloading);
    }

    public Path audio(String url, String format, Path into, DownloadProgress progress)
            throws IOException, InterruptedException {
        return ytDlp.downloadAudio(url, format, into, progress);
    }

    private Path merged(String url, MediaInfo info, MediaInfo.Format video, MediaInfo.Format audio,
                        Path into, DownloadProgress progress)
            throws IOException, InterruptedException {

        Path videoFile = into.resolve("stream-video." + extension(video, "mp4"));
        Path audioFile = into.resolve("stream-audio." + extension(audio, "m4a"));
        Path target = unique(into.resolve(fileName(info, video)));

        log.info("job files: {}p {} + {} -> {}", video.shortSide(), video.videoCodec(),
                audio.audioCodec(), target.getFileName());

        double videoShare = shareOfVideo(video, audio);
        try {
            ytDlp.downloadFormat(url, video.id(), videoFile,
                    fraction -> report(progress::downloading, fraction * videoShare));
            ytDlp.downloadFormat(url, audio.id(), audioFile,
                    fraction -> report(progress::downloading,
                            videoShare + fraction * (1 - videoShare)));

            // Announced before ffmpeg runs rather than from its first progress line: a stream copy
            // of a short video can finish without emitting one, and the phase would never be seen.
            progress.processing(0);
            ffmpeg.merge(videoFile, audioFile, target, info.durationSeconds(),
                    fraction -> report(progress::processing, fraction));
            report(progress::processing, 1.0);
            return target;

        } catch (IOException e) {
            YtDlp.deleteQuietly(target);
            throw e;
        } finally {
            YtDlp.deleteQuietly(videoFile);
            YtDlp.deleteQuietly(audioFile);
        }
    }

    private Path single(String url, MediaInfo info, MediaInfo.Format format, Path into,
                        DownloadProgress progress) throws IOException, InterruptedException {

        log.info("job files: {} whole -> one file", format.id());
        Path fetched = into.resolve("stream-single." + extension(format, "mp4"));
        try {
            ytDlp.downloadFormat(url, format.id(), fetched, progress::downloading);
            Path target = unique(into.resolve(fileName(info, format)));
            Files.move(fetched, target);
            return target;
        } catch (IOException e) {
            YtDlp.deleteQuietly(fetched);
            throw e;
        }
    }

    private Optional<MediaInfo> probe(String url) throws InterruptedException {
        try {
            MediaInfo info = probes.probe(url);
            return info.formats().isEmpty() ? Optional.empty() : Optional.of(info);
        } catch (IOException e) {
            log.warn("could not read the format list before downloading ({}) — letting yt-dlp "
                    + "choose", e.getMessage());
            return Optional.empty();
        }
    }

    private static void report(DoubleConsumer progress, double fraction) {
        if (progress != null) {
            progress.accept(Math.max(0, Math.min(1, fraction)));
        }
    }

    private static double shareOfVideo(MediaInfo.Format video, MediaInfo.Format audio) {
        long total = video.sizeBytes() + audio.sizeBytes();
        if (video.sizeBytes() <= 0 || total <= 0) {
            return VIDEO_SHARE;
        }
        return (double) video.sizeBytes() / total;
    }

    private static String extension(MediaInfo.Format format, String fallback) {
        String ext = format.extension();
        return ext == null || ext.isBlank() ? fallback : ext.toLowerCase(Locale.ROOT);
    }

    static String fileName(MediaInfo info, MediaInfo.Format format) {
        return fileName(info, format.shortSide());
    }

    static String fileName(MediaInfo info, int height) {
        String title = info.title() == null ? "" : info.title()
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", " ")
                .strip();
        if (title.isBlank()) {
            title = "video";
        }
        if (title.length() > MAX_NAME_CHARS) {
            title = title.substring(0, MAX_NAME_CHARS).strip();
        }
        return height > 0 ? "%s (%dp).mp4".formatted(title, height) : title + ".mp4";
    }

    private static Path unique(Path path) {
        if (!Files.exists(path)) {
            return path;
        }
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int counter = 1; ; counter++) {
            Path candidate = path.resolveSibling(base + " (" + counter + ")" + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }
}
