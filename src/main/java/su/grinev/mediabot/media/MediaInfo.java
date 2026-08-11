package su.grinev.mediabot.media;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What one host says about one video, before anything is downloaded.
 *
 * @param title as published; untrusted text, quote it before showing it to the model
 * @param uploader channel or account, same
 * @param durationSeconds 0 when the host did not say
 * @param isLive a stream has no size and no end, and downloading one is not what anybody meant
 * @param formats what can actually be fetched
 */
public record MediaInfo(String title, String uploader, int durationSeconds, boolean isLive,
                        List<Format> formats) {

    private static final List<String> PLAYS_ANYWHERE = List.of("avc1", "h264", "vp9", "vp09", "av01");
    /**
     * Cheapest to decode first, which is the opposite of smallest.
     *
     * <p>This used to prefer av01, on the reasoning that a source about to be re-encoded is thrown
     * away and so only its size matters. That is true of quality and false of time: AV1 costs several
     * times more to decode than H.264, and on a two-core box decoding the source is what the job
     * spends its minutes on. A 39-minute 1080p AV1 took seven minutes to re-encode where the same
     * material in avc1 would not have.
     */
    private static final List<String> CHEAP_TO_DECODE = List.of("avc1", "h264", "vp9", "vp09", "av01");
    private static final List<String> AUDIO_CODECS = List.of("mp4a", "aac", "opus", "vorbis");

    /** What the stream is being fetched for, which is what decides between codecs at one height. */
    public enum Purpose { DELIVERY, RE_ENCODING }

    /**
     * One fetchable stream.
     *
     * @param height 0 for audio-only
     * @param sizeBytes the host's own figure, exact or approximate; 0 when it gave none
     */
    public record Format(String id, String extension, int width, int height, double fps,
                         long sizeBytes, String videoCodec, String audioCodec) {

        public Format(String id, String extension, int height, double fps, long sizeBytes,
                      String videoCodec, String audioCodec) {
            this(id, extension, 0, height, fps, sizeBytes, videoCodec, audioCodec);
        }

        public int shortSide() {
            return width > 0 && width < height ? width : height;
        }

        public boolean hasVideo() {
            return present(videoCodec);
        }

        public boolean hasAudio() {
            return present(audioCodec);
        }

        private static boolean present(String codec) {
            return codec != null && !codec.isBlank() && !"none".equalsIgnoreCase(codec);
        }
    }

    /**
     * The largest this host will serve, measured the way a person asks for it.
     *
     * <p>The short side, not the frame height. "1080p" is a claim about the short side — a phone
     * recording is 1080x1920 and nobody calls it 1920p — and this number is compared against what
     * somebody typed. Measured as the frame height it made a vertical video look taller than it is,
     * so asking for 1440p of a 1080-wide reel passed the upscale check and produced a stretched
     * file instead of a refusal.
     */
    public int maxHeight() {
        return formats.stream()
                .filter(Format::hasVideo)
                .mapToInt(Format::shortSide)
                .max()
                .orElse(0);
    }

    /**
     * The distinct sizes on offer, largest first, for telling somebody what they can have.
     *
     * <p>Short sides, so the refusal quotes the numbers a person would have typed rather than the
     * frame heights of a vertical video.
     */
    public List<Integer> heights() {
        return formats.stream()
                .filter(Format::hasVideo)
                .map(Format::shortSide)
                .filter(h -> h > 0)
                .distinct()
                .sorted((a, b) -> Integer.compare(b, a))
                .toList();
    }

    public boolean hasAudioOnly() {
        return formats.stream().anyMatch(f -> f.hasAudio() && !f.hasVideo());
    }

    /**
     * The height to fetch in order to produce {@code targetHeight}.
     *
     * <p>The smallest rendition that is still at least as tall as the target: downscaling from just
     * above loses nothing anybody can see, while fetching the tallest and scaling it to 360p moves
     * a quarter of a gigabyte and decodes every one of its pixels to throw them away. When the host
     * publishes nothing that tall, the tallest it has is the best that can be done.
     */
    public Integer sourceHeightFor(int targetHeight) {
        return formats.stream()
                .filter(Format::hasVideo)
                .mapToInt(Format::shortSide)
                .filter(shortSide -> shortSide >= targetHeight)
                .min()
                .orElseGet(this::maxHeight);
    }

    public Optional<Format> pickVideo(Integer maxHeight) {
        return pickVideo(maxHeight, Purpose.DELIVERY);
    }

    public Optional<Format> pickVideo(Integer maxHeight, Purpose purpose) {
        return formats.stream()
                .filter(f -> f.hasVideo() && !f.hasAudio() && f.height() > 0)
                // Compared on the short side, because that is what the number means: a ceiling of
                // 720 on a vertical video has to allow 720x1280, not cut it to 405x720.
                .filter(f -> maxHeight == null || f.shortSide() <= maxHeight)
                .max(byVideoQuality(purpose));
    }

    public Optional<Format> pickAudio() {
        return formats.stream()
                .filter(f -> f.hasAudio() && !f.hasVideo())
                .max(Comparator.comparingInt((Format f) -> -rank(f.audioCodec(), AUDIO_CODECS))
                        .thenComparingLong(Format::sizeBytes));
    }

    public Optional<Format> pickProgressive(Integer maxHeight) {
        return pickProgressive(maxHeight, Purpose.DELIVERY);
    }

    public Optional<Format> pickProgressive(Integer maxHeight, Purpose purpose) {
        return formats.stream()
                .filter(f -> f.hasVideo() && f.hasAudio())
                .filter(f -> maxHeight == null || f.height() == 0 || f.shortSide() <= maxHeight)
                .max(byVideoQuality(purpose));
    }

    /**
     * What the delivered file would weigh at a given height.
     *
     * <p>Best effort by construction: the figure is whatever the host published, video and audio are
     * usually separate streams that have to be added together, and plenty of hosts publish nothing at
     * all. Used to warn before a download, never to promise — the real size is known when the file
     * exists.
     *
     * @param maxHeight ceiling, or null for the best available
     * @return the estimate in bytes, or empty when the host gave no figures to add up
     */
    public Optional<Long> estimatedSize(Integer maxHeight) {
        long video = pickVideo(maxHeight).map(Format::sizeBytes).orElse(0L);
        if (video > 0) {
            return Optional.of(video + pickAudio().map(Format::sizeBytes).orElse(0L));
        }
        return pickProgressive(maxHeight).map(Format::sizeBytes).filter(size -> size > 0);
    }

    private static Comparator<Format> byVideoQuality(Purpose purpose) {
        boolean reEncoding = purpose == Purpose.RE_ENCODING;
        List<String> codecs = reEncoding ? CHEAP_TO_DECODE : PLAYS_ANYWHERE;
        Comparator<Format> comparator = Comparator.comparingInt(Format::height)
                .thenComparingDouble(Format::fps)
                .thenComparingInt(f -> -rank(f.videoCodec(), codecs));
        return reEncoding
                ? comparator.thenComparingLong(f -> -f.sizeBytes())
                : comparator.thenComparingLong(Format::sizeBytes);
    }

    private static int rank(String codec, List<String> preferred) {
        if (codec == null) {
            return preferred.size();
        }
        String lower = codec.toLowerCase(Locale.ROOT);
        for (int i = 0; i < preferred.size(); i++) {
            if (lower.startsWith(preferred.get(i))) {
                return i;
            }
        }
        return preferred.size();
    }
}
