package su.grinev.mediabot.route;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What was written next to a command, and nothing about which command it was.
 *
 * <p>One place where a height, a job number, a chat id or a count is recognised, so every command
 * reads its arguments the same way and argument order stays free everywhere. The link is already
 * separated out by the time this exists: {@code words} is the message with it removed.
 */
public record Args(long chatId, String url, String words) {

    /** 720, 720p, 720п — the last because a Russian keyboard layout produces it constantly. */
    private static final Pattern HEIGHT = Pattern.compile(
            "\\b(2160|1440|1080|720|480|360|240|144)\\s*[pп]?\\b");

    private static final Pattern FOUR_K = Pattern.compile("\\b4\\s*k\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern COUNT = Pattern.compile("\\b(\\d{1,2})\\b");

    private static final Pattern JOB_NUMBER = Pattern.compile("#?\\b(\\d{1,6})\\b");

    /** Nine or ten digits for a person, negative for a group — not the same thing as a job number. */
    private static final Pattern CHAT_ID = Pattern.compile("-?\\d{5,15}");

    private static final int[] KNOWN_HEIGHTS = {144, 240, 360, 480, 720, 1080, 1440, 2160};

    private static final int MAX_PLAYLIST_COUNT = 25;

    public boolean hasUrl() {
        return url != null;
    }

    public Integer height() {
        if (FOUR_K.matcher(words).find()) {
            return 2160;
        }
        Matcher m = HEIGHT.matcher(words);
        if (!m.find()) {
            return null;
        }
        int height = Integer.parseInt(m.group(1));
        return isKnownHeight(height) ? height : null;
    }

    public String audioFormat() {
        if (words.contains("mp3")) {
            return "mp3";
        }
        return words.contains("opus") ? "opus" : null;
    }

    /** How many of a playlist to take; a height is a number too and is not one of them. */
    public int count(int fallback) {
        Matcher m = COUNT.matcher(words);
        while (m.find()) {
            int number = Integer.parseInt(m.group(1));
            if (number >= 1 && number <= MAX_PLAYLIST_COUNT && !isKnownHeight(number)) {
                return number;
            }
        }
        return fallback;
    }

    public Optional<Long> jobNumber() {
        Matcher m = JOB_NUMBER.matcher(words);
        return m.find() ? Optional.of(Long.parseLong(m.group(1))) : Optional.empty();
    }

    public Optional<Long> chatIdArgument() {
        Matcher m = CHAT_ID.matcher(words);
        return m.find() ? Optional.of(Long.parseLong(m.group())) : Optional.empty();
    }

    /** Whatever follows the chat id, which is the note somebody attached to an /allow. */
    public String noteAfter(long id) {
        int at = words.indexOf(String.valueOf(id));
        String rest = at < 0 ? "" : words.substring(at + String.valueOf(id).length());
        return rest.isBlank() ? null : rest.strip();
    }

    /**
     * The words with every argument this class knows how to read taken out, so what is left is by
     * definition something the grammar did not use.
     */
    public static String withoutKnownArguments(String words) {
        String left = FOUR_K.matcher(words).replaceAll(" ");
        return HEIGHT.matcher(left).replaceAll(" ");
    }

    private static boolean isKnownHeight(int height) {
        for (int known : KNOWN_HEIGHTS) {
            if (known == height) {
                return true;
            }
        }
        return false;
    }
}
