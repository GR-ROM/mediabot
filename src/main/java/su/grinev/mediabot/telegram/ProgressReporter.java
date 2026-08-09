package su.grinev.mediabot.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import su.grinev.mediabot.jobs.Job;
import su.grinev.mediabot.jobs.ProgressSink;
import su.grinev.mediabot.text.Sizes;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One message per job, rewritten as it goes.
 *
 * <p>Throttled, and that is not a nicety. Telegram rate-limits edits, and a download reporting every
 * percent would issue a hundred of them, get itself limited, and then fail to deliver the messages
 * that actually matter. The interval is generous for the same reason: nobody watching a download
 * needs it smooth, they need to know it is alive.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProgressReporter implements ProgressSink {

    /** Telegram tolerates roughly one edit a second per chat; this stays well clear. */
    private static final long MIN_EDIT_INTERVAL_MS = 4_000;

    /** Progress worth redrawing for. Below this the bar looks busy and says nothing. */
    private static final double MIN_STEP = 0.05;

    /** A progress line is read at a glance; a full title turns it into a paragraph. */
    private static final int TITLE_CHARS = 48;

    private record Line(int messageId, long lastEditMs, double lastFraction) {}

    private final MediaBot bot;
    private final Map<Long, Line> lines = new ConcurrentHashMap<>();

    @Override
    public void started(Job job) {
        bot.say(job.chatId(), "⏳ Downloading " + shortTitle(job))
                .ifPresent(messageId -> lines.put(job.id(), new Line(messageId, 0, -1)));
    }

    @Override
    public void progress(Job job, double fraction) {
        Line line = lines.get(job.id());
        if (line == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - line.lastEditMs() < MIN_EDIT_INTERVAL_MS
                || fraction - line.lastFraction() < MIN_STEP) {
            return;
        }
        // Replaced before the edit, not after: two progress callbacks land close together, and
        // updating afterwards lets both through the check above.
        lines.put(job.id(), new Line(line.messageId(), now, fraction));
        bot.edit(job.chatId(), line.messageId(),
                "%s %.0f%% %s".formatted(bar(fraction), fraction * 100, shortTitle(job)));
    }

    @Override
    public void finished(Job job) {
        Line line = lines.remove(job.id());
        if (line == null) {
            return;
        }
        // The progress line has served its purpose; what replaces it is the outcome in one line.
        // The file itself, or the explanation, comes from the dispatcher.
        bot.edit(job.chatId(), line.messageId(), switch (job.state()) {
            case DONE -> "✅ %s (%s)".formatted(shortTitle(job), Sizes.bytes(job.sizeBytes()));
            case TOO_BIG -> "📦 %s — %s, too large to hand over"
                    .formatted(shortTitle(job), Sizes.bytes(job.sizeBytes()));
            case FAILED -> "❌ %s".formatted(shortTitle(job));
            case CANCELLED -> "🚫 %s — cancelled".formatted(shortTitle(job));
            default -> shortTitle(job);
        });
    }

    private static String shortTitle(Job job) {
        String title = job.describe().replaceAll("\\s+", " ").strip();
        return title.length() <= TITLE_CHARS ? title
                : title.substring(0, TITLE_CHARS - 1).strip() + "…";
    }

    /** The message the progress line lives in, so a caller can replace it rather than add to it. */
    public Optional<Integer> messageFor(long jobId) {
        return Optional.ofNullable(lines.get(jobId)).map(Line::messageId);
    }

    private static String bar(double fraction) {
        int filled = (int) Math.round(Math.max(0, Math.min(1, fraction)) * 10);
        return "▰".repeat(filled) + "▱".repeat(10 - filled);
    }
}
