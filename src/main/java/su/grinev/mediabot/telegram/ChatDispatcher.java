package su.grinev.mediabot.telegram;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import su.grinev.mediabot.AgentProperties;
import su.grinev.mediabot.access.AccessList;
import su.grinev.mediabot.media.AudioFormat;
import su.grinev.mediabot.media.Container;
import su.grinev.mediabot.media.Quality;
import su.grinev.mediabot.graph.Pipeline;
import su.grinev.mediabot.jobs.DownloadRequests;
import su.grinev.mediabot.jobs.Job;
import su.grinev.mediabot.jobs.JobFinished;
import su.grinev.mediabot.jobs.JobQueue;
import su.grinev.mediabot.jobs.JobSpec;
import su.grinev.mediabot.jobs.JobState;
import su.grinev.mediabot.links.ShortLink;
import su.grinev.mediabot.links.ShortLinkService;
import su.grinev.mediabot.llm.IntentParser;
import su.grinev.mediabot.llm.PipelineUpgrade;
import su.grinev.mediabot.media.UrlGuard;
import su.grinev.mediabot.media.YtDlp;
import su.grinev.mediabot.route.Command;
import su.grinev.mediabot.route.Request;
import su.grinev.mediabot.route.RequestRouter;
import su.grinev.mediabot.text.Sizes;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Everything a chat message can cause, and nothing it cannot.
 *
 * <p>A message is read by {@link RequestRouter} into one of six requests, each answered from a
 * template. Only a message the router cannot read reaches {@link IntentParser}, and all that comes
 * back from there is a {@code JobSpec} — validated, queued through the same {@link DownloadRequests}
 * as everything else, and never a sentence anybody reads.
 *
 * <p>There was an agent here: a tool-calling loop with a transcript per chat. It was removed after
 * it announced a queued job that did not exist, invented three links, and made up a job number, all
 * of which had to be defended against in code anyway. What it was for — reading "720p" out of a
 * sentence — is a dozen stems and a regex.
 */
@Slf4j
@Component
public class ChatDispatcher {

    private static final int RECENT = 16;

    /**
     * Every list in here is generated: the commands from {@link Command}, the parameters from the
     * enums that accept them. Written by hand it drifted twice — a command that had been renamed,
     * and one that was never there — and a help text that lies is worse than none.
     */
    private static final String HELP = ("""
            Send me a link and I will fetch it and hand it back — as a file when it is small
            enough to travel, as a link when it is not.

            COMMANDS
            Each also has a plain-English spelling that means the same thing.

            Steps chain, and the slash is the separator:
              /download <link> /cut 1:00-2:00 2:00-2:20 /encode HIGH /normalize

              /cut 1:00-2:00 [2:40-3:00 ...]  one range per piece — two ranges, two files
              /encode [QUALITIES] [CONTAINERS] [720p]
              /normalize                      even out the loudness
              /audio [FORMATS]                the sound alone
              /join                           put the pieces back into one file
              /publish                        hand it over, added at the end for you

            Profiles are a size and a squeeze together: LOW 360p, STANDARD 720p, HIGH 1080p,
            MAX keeps the source's own size. A height written next to a profile wins over the
            profile's own. /transcode, /compress and /convert all mean /encode.

            Order is the meaning. /audio /cut takes the sound and cuts that, and never fetches
            the video; /cut /audio cuts the video and takes the sound of each piece.

            /cut on its own copies the streams, which costs nothing but can only start on a
            keyframe — the piece may begin a few seconds before you asked. Followed by /encode
            it is exact to the frame, because the re-encode has to decode anyway."""
            ).replace("COMMANDS", Command.everyday())
             .replace("QUALITIES", Quality.spelled())
             .replace("CONTAINERS", Container.spelled())
             .replace("FORMATS", AudioFormat.spelled());

    private static final String OWNER_HELP = System.lineSeparator() + System.lineSeparator()
            + "Yours only:" + System.lineSeparator() + Command.ownerOnlyListing()
            + System.lineSeparator()
            + "Somebody's id shows up in the log the first time they write to me.";

    private final MediaBot bot;
    private final AccessList access;
    private final RequestRouter router;
    private final IntentParser parser;
    private final PipelineUpgrade upgrade;
    private final DownloadRequests requests;
    private final JobQueue queue;
    private final AdminReplies admin;
    private final Deliveries deliveries;
    private final YtDlp ytDlp;
    private final UrlGuard guard;
    private final AgentProperties props;

    public ChatDispatcher(MediaBot bot, AccessList access, RequestRouter router,
                          IntentParser parser,
                          PipelineUpgrade upgrade, DownloadRequests requests, JobQueue queue,
                          AdminReplies admin, Deliveries deliveries,
                          YtDlp ytDlp, UrlGuard guard, AgentProperties props) {
        this.bot = bot;
        this.access = access;
        this.router = router;
        this.parser = parser;
        this.upgrade = upgrade;
        this.requests = requests;
        this.queue = queue;
        this.admin = admin;
        this.deliveries = deliveries;
        this.ytDlp = ytDlp;
        this.guard = guard;
        this.props = props;
        bot.handleWith(this::onMessage);
    }

    // ---------------------------------------------------------------- incoming

    void onMessage(long chatId, String text) {
        if (!access.permits(chatId)) {
            // Printed so it can be copied: somebody being let in starts with them writing to the
            // bot and this line being the only place their id appears.
            log.warn("ignored a message from chat {} — not on the allowlist. To let it in, send "
                    + "the bot: /allow {}", chatId, chatId);
            return;
        }
        try {
            Optional<Request> read = router.parse(chatId, text);
            if (read.isPresent()) {
                // Read literally, and then offered to the model to grow — which it may only do in
                // one direction. Anything else and this is the reading that is answered.
                answer(chatId, upgrade.applyTo(chatId, text, read.get()));
                return;
            }
            // Nothing literal in it. One question to the model, which may only answer with a job.
            Optional<JobSpec> parsed = parser.parse(chatId, text);
            if (parsed.isPresent()) {
                answer(chatId, new Request.Fetch(parsed.get()));
                return;
            }
            bot.say(chatId, "I could not read that.\n\n" + help(chatId));

        } catch (Command.Incomplete e) {
            // Recognised and unserviceable, which is not the same as unreadable: say what this one
            // command takes rather than handing over the whole manual.
            bot.say(chatId, e.getMessage() + ".");

        } catch (Pipeline.Invalid e) {
            // A chain that does not parse knows exactly what stopped it, and saying so is the whole
            // reason the steps are spelled out rather than described.
            bot.say(chatId, "I could not run that: " + e.getMessage() + ".");

        } catch (Exception e) {
            log.error("message from chat {} failed", chatId, e);
            bot.say(chatId, "That went wrong: " + reason(e));
        }
    }

    private void answer(long chatId, Request request) {
        switch (request) {
            case Request.Fetch(JobSpec spec) -> fetch(chatId, spec);
            case Request.Playlist(String url, Integer height, int count) -> playlist(chatId, url, height, count);
            case Request.Status() -> status(chatId);
            case Request.Link(Long jobId) -> link(chatId, jobId);
            case Request.Cancel(long jobId) -> cancel(chatId, jobId);
            case Request.Help() -> bot.say(chatId, help(chatId));
            case Request.Allow(long who, String note) -> admin.allow(chatId, who, note);
            case Request.Deny(long who) -> admin.deny(chatId, who);
            case Request.Allowed() -> admin.allowed(chatId);
        }
    }

    private String help(long chatId) {
        return access.isOwner(chatId) ? HELP + OWNER_HELP : HELP;
    }

    private void fetch(long chatId, JobSpec asked) {
        // Clamped here, once, on the way into the queue: every path — a bare link, a slash command,
        // a chain, the model's answer — arrives through this one method.
        int ceiling = access.ceilingFor(chatId);
        JobSpec spec = asked.cappedAt(ceiling);
        String note = java.util.Objects.equals(asked.maxHeight(), spec.maxHeight()) ? ""
                : " Capped at %dp, which is the most this chat may ask for.".formatted(ceiling);

        switch (requests.submit(spec, asked.maxHeight())) {
            case DownloadRequests.Outcome.Refused(String reason) ->
                    bot.say(chatId, "I cannot do that: " + reason + ".");

            case DownloadRequests.Outcome.Queued(var job, _, var estimate) ->
                    bot.say(chatId, "Queued as job %d (%s).%s I will post a link here when it is "
                            .formatted(job.id(), spec.describe(),
                                    estimate.map(size -> " About " + Sizes.bytes(size) + ".")
                                            .orElse(""))
                            + "ready." + note);
        }
    }

    private void playlist(long chatId, String rawUrl, Integer wanted, int count) {
        Integer maxHeight = Math.min(wanted == null ? Integer.MAX_VALUE : wanted,
                access.ceilingFor(chatId));
        String url;
        try {
            url = guard.check(rawUrl);
        } catch (UrlGuard.Rejected e) {
            bot.say(chatId, "I cannot do that: " + e.getMessage() + ".");
            return;
        }
        List<YtDlp.PlaylistItem> items;
        try {
            items = ytDlp.playlistItems(url, count);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (IOException e) {
            bot.say(chatId, "I could not read that playlist: " + e.getMessage() + ".");
            return;
        }
        if (items.isEmpty()) {
            bot.say(chatId, "Nothing that looks like a playlist is at that link. Send a single "
                    + "video and I will fetch it.");
            return;
        }

        int queued = 0;
        StringBuilder refused = new StringBuilder();
        for (YtDlp.PlaylistItem item : items) {
            switch (requests.submit(JobSpec.download(chatId, item.url(), maxHeight))) {
                case DownloadRequests.Outcome.Queued(var job, _, _) -> queued++;
                // One video that cannot be had is not a reason to abandon the other nine.
                case DownloadRequests.Outcome.Refused(String reason) ->
                        refused.append(System.lineSeparator())
                                .append("  %s — %s".formatted(item.title(), reason));
            }
        }
        bot.say(chatId, "Queued %d of %d from the playlist%s. Links arrive here as they finish.%s"
                .formatted(queued, items.size(),
                        maxHeight == null ? "" : ", up to " + maxHeight + "p",
                        refused.isEmpty() ? "" : System.lineSeparator() + "Could not queue:"
                                + refused));
    }

    private void status(long chatId) {
        List<Job> recent = queue.recentIn(chatId, RECENT);
        if (recent.isEmpty()) {
            bot.say(chatId, "Nothing has been downloaded in this chat yet.");
            return;
        }
        StringBuilder answer = new StringBuilder("%d queued or running, most recent first:"
                .formatted(queue.pendingIn(chatId).size()));
        recent.forEach(job -> answer.append(System.lineSeparator()).append("  ").append(describe(job)));
        bot.say(chatId, answer.toString());
    }

    private void link(long chatId, Long jobId) {
        Optional<Job> found = jobId == null
                ? queue.recentIn(chatId, RECENT).stream()
                        .filter(job -> job.state() == JobState.DONE)
                        .findFirst()
                : queue.find(jobId).filter(job -> job.chatId() == chatId);

        if (found.isEmpty()) {
            bot.say(chatId, jobId == null
                    ? "Nothing has finished downloading in this chat yet."
                    : "There is no job " + jobId + " in this chat.");
            return;
        }
        Job job = found.get();
        if (job.state() != JobState.DONE) {
            bot.say(chatId, "Job %d is %s, so there is no file yet."
                    .formatted(job.id(), job.state().name().toLowerCase(java.util.Locale.ROOT)));
            return;
        }
        try {
            deliveries.resend(chatId, job);
        } catch (IOException e) {
            bot.say(chatId, "The file for job %d is gone, so I cannot link to it again. Send the "
                    .formatted(job.id()) + "link and I will fetch it once more.");
        }
    }

    private void cancel(long chatId, long jobId) {
        Optional<Job> cancelled = queue.cancel(chatId, jobId);
        if (cancelled.isEmpty()) {
            bot.say(chatId, "There is no job " + jobId + " in this chat.");
            return;
        }
        Job job = cancelled.get();
        bot.say(chatId, job.state() == JobState.CANCELLED
                ? "Job %d is cancelled.".formatted(jobId)
                : "Job %d had already finished as %s.".formatted(jobId, job.state()));
    }

    private static String reason(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static String describe(Job job) {
        String what = job.title() == null || job.title().isBlank() ? job.url() : job.title();
        return switch (job.state()) {
            case PENDING -> "#%d waiting — %s".formatted(job.id(), what);
            case DOWNLOADING -> "#%d downloading — %s".formatted(job.id(), what);
            case PROCESSING -> "#%d processing — %s".formatted(job.id(), what);
            case DONE -> "#%d done (%s) — %s".formatted(job.id(), Sizes.bytes(job.sizeBytes()), what);
            case EXPIRED -> "#%d expired, ask again to re-download — %s".formatted(job.id(), what);
            case TOO_BIG -> "#%d too large to hand over (%s) — %s".formatted(job.id(), Sizes.bytes(job.sizeBytes()), what);
            case FAILED -> "#%d failed: %s — %s".formatted(job.id(), job.error(), what);
            case INTERRUPTED -> "#%d interrupted by a restart — %s".formatted(job.id(), what);
            case CANCELLED -> "#%d cancelled — %s".formatted(job.id(), what);
        };
    }

}
