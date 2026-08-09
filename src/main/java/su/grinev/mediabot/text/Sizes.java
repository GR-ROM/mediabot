package su.grinev.mediabot.text;

import lombok.experimental.UtilityClass;

import java.util.Locale;

/**
 * Numbers the way they are said out loud, since every one of these ends up in a chat message.
 *
 * <p>Always {@link Locale#ROOT}: a size formatted with the host machine's locale would change its
 * decimal separator depending on where the bot happens to be deployed.
 */
@UtilityClass
public class Sizes {

    public String bytes(long value) {
        if (value <= 0) {
            return "size unknown";
        }
        if (value < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.0f KB", value / 1024.0);
        }
        if (value < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.0f MB", value / (1024.0 * 1024));
        }
        return String.format(Locale.ROOT, "%.1f GB", value / (1024.0 * 1024 * 1024));
    }

    public String duration(int seconds) {
        if (seconds <= 0) {
            return "length unknown";
        }
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int rest = seconds % 60;
        return hours > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest)
                : String.format(Locale.ROOT, "%d:%02d", minutes, rest);
    }
}
