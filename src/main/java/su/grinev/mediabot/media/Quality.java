package su.grinev.mediabot.media;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * How hard to squeeze, as four names instead of two numbers.
 *
 * <p>A profile is a height and a quantiser together, because on their own neither is an answer:
 * 480p at a low quantiser is a big file of a small picture, and 1080p at a high one is a large
 * picture nobody wants to look at. The pairs here are the combinations that are worth asking for.
 *
 * <p>A height written next to a profile wins over the profile's own — the profile is then only
 * saying how hard to squeeze at that size.
 */
public enum Quality {

    /** Small enough to send anywhere, and it shows. */
    LOW(360, 28, 36),

    /** What most things should be. */
    STANDARD(720, 23, 31),

    /** Worth it on a big screen. */
    HIGH(1080, 20, 28),

    /** Keeps the source's own size and spends bytes to stay close to it. */
    MAX(null, 18, 24);

    public static final Quality DEFAULT = STANDARD;

    private final Integer height;
    private final int crf;
    private final int vp9Crf;

    Quality(Integer height, int crf, int vp9Crf) {
        this.height = height;
        this.crf = crf;
        this.vp9Crf = vp9Crf;
    }

    /** The height this profile aims for, or null for "whatever the source is". */
    public Integer height() {
        return height;
    }

    /** The constant-rate factor for this container; vp9's scale is not x264's. */
    public int crf(Container container) {
        return container.isVp9() ? vp9Crf : crf;
    }

    public static Optional<Quality> named(String word) {
        if (word == null) {
            return Optional.empty();
        }
        String upper = word.toUpperCase(Locale.ROOT);
        // STANDART is how it is spelled by half the people who reach for it, and refusing that
        // spelling teaches nothing.
        String canonical = upper.equals("STANDART") ? "STANDARD" : upper;
        return Arrays.stream(values()).filter(q -> q.name().equals(canonical)).findFirst();
    }

    public static String spelled() {
        return Arrays.stream(values()).map(Enum::name)
                .collect(java.util.stream.Collectors.joining("|"));
    }
}
