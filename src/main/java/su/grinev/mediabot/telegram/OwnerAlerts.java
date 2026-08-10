package su.grinev.mediabot.telegram;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import su.grinev.mediabot.access.AccessList;
import su.grinev.mediabot.jobs.Job;
import su.grinev.mediabot.media.YtDlp;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The failures the owner has to do something about, told to the owner.
 *
 * <p>Everything else the bot says goes to whoever asked. This one cannot: a person sent a link and
 * was told the host wants proof the bot is not a bot, which is true, unhelpful and not theirs to
 * fix. The person who can fix it is not in that conversation and would otherwise find out by
 * somebody complaining.
 *
 * <p>Quiet by default and rate-limited by construction. A stale credential fails every job, and a
 * message per failed job would be its own outage — so the first one is said and the rest for the
 * next hour are not.
 */
@Component
@Slf4j
public class OwnerAlerts {

    /** Long enough that a bad afternoon is one message, short enough to notice a new one. */
    private static final Duration QUIET = Duration.ofHours(1);

    private final MediaBot bot;
    private final AccessList access;
    private final AtomicReference<Instant> lastSaid = new AtomicReference<>(Instant.EPOCH);

    public OwnerAlerts(MediaBot bot, AccessList access) {
        this.bot = bot;
        this.access = access;
    }

    /**
     * Considers a finished job, and says something only if it is the failure that needs an owner.
     *
     * <p>Matched on the sentence {@code YtDlp} produces rather than on the raw stderr, because that
     * is the one place the failures are classified and this must not become a second one.
     */
    /**
     * The provider stopped answering, which is the same problem found earlier.
     *
     * <p>Worth its own entry point because it arrives from somewhere else — a warning on a run that
     * worked, or the canary's hourly look — and because it is the one that arrives before anybody
     * has lost a download. It shares the quiet period with the rest, so a broken provider is one
     * message however many places notice it.
     */
    @org.springframework.context.event.EventListener
    public void onProviderUnreachable(su.grinev.mediabot.media.ProviderUnreachable event) {
        if (!worthSaying()) {
            return;
        }
        say(("YouTube downloads are about to start failing: the proof-of-origin provider is not "
                + "answering.%n%n%s%n%nCheck the potoken container. Nothing has broken for anybody "
                + "yet — this is the warning before it does.").formatted(event.detail()));
    }

    public void consider(Job job) {
        if (job == null || !YtDlp.NEEDS_AUTHENTICATION.equals(job.error())) {
            return;
        }
        if (!worthSaying()) {
            log.debug("job {} hit the bot check again, already said", job.id());
            return;
        }
        say(("YouTube is refusing downloads: it wants proof this is not a bot, and job %d failed on "
                + "it.%n%nNothing in the chat can fix that. Either the proof-of-origin provider is "
                + "not answering — check the potoken container — or, if you are running on cookies "
                + "instead, they have gone stale and need exporting again.").formatted(job.id()));
    }

    private void say(String message) {
        access.owners().forEach(owner -> bot.say(owner, message));
        log.warn("told {} owner(s): {}", access.owners().size(),
                message.lines().findFirst().orElse(message));
    }

    /**
     * True once per quiet period, for whoever gets there first.
     *
     * <p>Compare-and-set rather than a lock: two workers can fail on the same credential in the same
     * millisecond, and only one of them should be the one that says so.
     */
    private boolean worthSaying() {
        Instant now = Instant.now();
        Instant previous = lastSaid.get();
        return Duration.between(previous, now).compareTo(QUIET) >= 0
                && lastSaid.compareAndSet(previous, now);
    }
}
