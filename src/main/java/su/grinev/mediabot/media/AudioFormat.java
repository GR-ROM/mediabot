package su.grinev.mediabot.media;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What the sound alone is written as.
 *
 * <p>m4a is the default and not a preference: it is the one an iPhone plays without argument, and
 * opus, which is smaller and better, is the one it does not.
 */
public enum AudioFormat {

    M4A("m4a", List.of("-c:a", "aac", "-b:a", "192k")),
    MP3("mp3", List.of("-c:a", "libmp3lame", "-q:a", "2")),
    OPUS("opus", List.of("-c:a", "libopus", "-b:a", "128k"));

    public static final AudioFormat DEFAULT = M4A;

    private final String extension;
    private final List<String> codec;

    AudioFormat(String extension, List<String> codec) {
        this.extension = extension;
        this.codec = codec;
    }

    public String extension() {
        return extension;
    }

    public List<String> codec() {
        return codec;
    }

    public static Optional<AudioFormat> named(String word) {
        if (word == null) {
            return Optional.empty();
        }
        String lower = word.toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(f -> f.extension.equals(lower)).findFirst();
    }

    public static AudioFormat orDefault(String word) {
        return named(word).orElse(DEFAULT);
    }

    public static String spelled() {
        return Arrays.stream(values()).map(AudioFormat::extension)
                .collect(java.util.stream.Collectors.joining("|"));
    }
}
