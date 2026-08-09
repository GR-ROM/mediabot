package su.grinev.mediabot.media;

import java.util.Locale;

/**
 * A window to keep out of a video.
 *
 * <p>Seconds rather than a string, because "1:30" is a thing a person types and not a thing ffmpeg
 * should be handed unparsed.
 *
 * @param startSeconds where to begin, 0 for the beginning
 * @param endSeconds where to stop, or null for "to the end"
 */
public record Trim(int startSeconds, Integer endSeconds) {

    public Trim {
        if (startSeconds < 0) {
            throw new IllegalArgumentException("a cut cannot start before the video does");
        }
        if (endSeconds != null && endSeconds.equals(startSeconds)) {
            throw new IllegalArgumentException("a cut from %s to %s is empty — the end has to come "
                    .formatted(clock(startSeconds), clock(endSeconds)) + "after the start");
        }
        if (endSeconds != null && endSeconds < startSeconds) {
            throw new IllegalArgumentException("a cut has to end after it starts, and %s comes "
                    .formatted(clock(endSeconds)) + "before " + clock(startSeconds));
        }
    }

    public static Trim firstSeconds(int seconds) {
        return new Trim(0, seconds);
    }

    public static Trim range(String text) {
        String written = text.strip();
        String[] ends = written.split("\\s*(?:--|–|—|-|\\bto\\b)\\s*", 2);
        if (ends.length < 2) {
            throw new IllegalArgumentException("\"%s\" is one timecode, not a range — a range needs "
                    .formatted(written) + "a start and an end, written 1:00-2:00");
        }
        if (ends[0].isBlank()) {
            throw new IllegalArgumentException("\"%s\" has no start — a range runs from one "
                    .formatted(written) + "timecode to another, written 1:00-2:00");
        }
        int start = seconds(ends[0]);
        return ends[1].isBlank() ? new Trim(start, null) : new Trim(start, seconds(ends[1]));
    }

    public static int seconds(String clock) {
        String written = clock.strip();
        String[] parts = written.split(":", -1);
        if (parts.length > 3) {
            throw new IllegalArgumentException("\"%s\" has too many parts — a timecode is h:mm:ss "
                    .formatted(written) + "at most");
        }
        int total = 0;
        for (String part : parts) {
            boolean digits = parts.length == 1 ? part.matches("\\d{1,5}") : part.matches("\\d{1,2}");
            if (!digits) {
                throw new IllegalArgumentException(part.isBlank()
                        ? "\"%s\" has an empty part — a timecode is written 1:30, 0:01:30 or 90"
                                .formatted(written)
                        : "\"%s\" is not a timecode — write it as 1:30, 0:01:30 or as plain seconds"
                                .formatted(written));
            }
            total = total * 60 + Integer.parseInt(part);
        }
        return total;
    }

    /** How long the kept piece is, or null when it runs to the end. */
    public Integer durationSeconds() {
        return endSeconds == null ? null : endSeconds - startSeconds;
    }

    public String describe() {
        return endSeconds == null
                ? "from " + clock(startSeconds)
                : clock(startSeconds) + "–" + clock(endSeconds);
    }

    private static String clock(int seconds) {
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
