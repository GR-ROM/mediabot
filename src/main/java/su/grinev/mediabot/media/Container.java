package su.grinev.mediabot.media;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What a re-encoded file is wrapped in, and what goes inside it.
 *
 * <p>A closed set rather than whatever string arrived: the container decides the codecs, and a
 * codec chosen from an unchecked string is an ffmpeg command that fails minutes into a job instead
 * of a sentence refused before one is queued.
 */
public enum Container {
    MP4("mp4", true, Codecs.H264, Codecs.AAC),
    MOV("mov", true, Codecs.H264, Codecs.AAC),
    MKV("mkv", false, Codecs.H264, Codecs.AAC),
    WEBM("webm", false, List.of("-c:v", "libvpx-vp9", "-b:v", "0"), List.of("-c:a", "libopus", "-b:a", "128k"));

    public static final Container DEFAULT = MP4;

    /**
     * The codec arguments, in a holder because an enum constant may not refer forward to a static
     * field of its own class.
     */
    private static final class Codecs {

        /**
         * H.264 as something that will actually play, rather than as whatever x264 felt like
         * producing.
         *
         * <p>The two arguments after the preset are the ones that were missing. A ten-bit source
         * gives x264 ten-bit input, and left alone it encodes ten-bit output in the High 10
         * profile — which nothing Apple makes decodes: not the phone, not the Mac, not Quick Look.
         * It is not a container problem and not a bitrate problem, so it arrives looking like a
         * corrupt file rather than an unsupported one.
         *
         * <p>Forcing 8-bit 4:2:0 and the High profile costs nothing anybody can see, and is what
         * every device on earth decodes in hardware.
         */
        static final List<String> H264 = List.of("-c:v", "libx264", "-preset", "veryfast",
                "-pix_fmt", "yuv420p", "-profile:v", "high");

        static final List<String> AAC = List.of("-c:a", "aac", "-b:a", "128k");

        private Codecs() {
        }
    }

    private final String extension;
    private final boolean faststart;
    private final List<String> video;
    private final List<String> audio;

    Container(String extension, boolean faststart, List<String> video, List<String> audio) {
        this.extension = extension;
        this.faststart = faststart;
        this.video = video;
        this.audio = audio;
    }

    public String extension() {
        return extension;
    }

    /** Puts the index at the front so the file can start playing before it is fully downloaded. */
    public boolean wantsFaststart() {
        return faststart;
    }

    public boolean isVp9() {
        return this == WEBM;
    }

    public List<String> videoCodec() {
        return video;
    }

    public List<String> audioCodec() {
        return audio;
    }

    public static Optional<Container> named(String word) {
        if (word == null) {
            return Optional.empty();
        }
        String lower = word.toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(c -> c.extension.equals(lower)).findFirst();
    }

    public static Container ofExtension(String extension) {
        return named(extension).orElse(DEFAULT);
    }

    public static String spelled() {
        return Arrays.stream(values()).map(Container::extension)
                .collect(java.util.stream.Collectors.joining("|"));
    }
}
