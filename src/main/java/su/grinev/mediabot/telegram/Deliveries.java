package su.grinev.mediabot.telegram;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import su.grinev.mediabot.AgentProperties;
import su.grinev.mediabot.jobs.Job;
import su.grinev.mediabot.jobs.JobFinished;
import su.grinev.mediabot.links.ShortLink;
import su.grinev.mediabot.links.ShortLinkService;
import su.grinev.mediabot.text.Sizes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Telling a chat what became of its job.
 *
 * <p>Everything the bot says without being asked lives here: the file or the link when it worked,
 * and the sentence explaining it when it did not. Split from the dispatcher because the two are
 * driven by different things — one answers messages as they arrive, this one answers a worker
 * thread finishing minutes later — and because a job is announced whether or not anybody is still
 * in the conversation that started it.
 */
@Component
@Slf4j
public class Deliveries {

    private final MediaBot bot;
    private final ShortLinkService shortLinks;
    private final AgentProperties props;

    public Deliveries(MediaBot bot, ShortLinkService shortLinks, AgentProperties props) {
        this.bot = bot;
        this.shortLinks = shortLinks;
        this.props = props;
    }

    /**
     * A job has stopped being pending. This is the callback the whole queue exists for.
     *
     * <p>Runs on the worker thread that finished the job. Every outcome is a template: there is
     * nothing here worth a round trip to a model, and nothing a model could add that it would not
     * eventually get wrong.
     */
    @EventListener
    public void onJobFinished(JobFinished event) {
        Job job = event.job();
        switch (job.state()) {
            case DONE -> deliver(job);
            case TOO_BIG -> bot.say(job.chatId(), ("%s came out %s, which is over the %s I hand "
                    + "over, so it was not published. Ask for a smaller height.")
                    .formatted(job.describe(), Sizes.bytes(job.sizeBytes()),
                            Sizes.bytes(props.telegram().maxUploadBytes())));
            case FAILED -> bot.say(job.chatId(),
                    "%s failed: %s.".formatted(job.describe(), job.error()));
            case INTERRUPTED -> bot.say(job.chatId(), ("Job %d (%s) was interrupted when the bot "
                    + "restarted. Send the link again if you still want it.")
                    .formatted(job.id(), job.describe()));

            default -> log.debug("job {} finished as {}, nothing to say", job.id(), job.state());
        }
    }

    private void deliver(Job job) {
        if (job.results().isEmpty()) {
            log.error("job {} is DONE with no file", job.id());
            return;
        }
        try {
            hand(job, shortLinks.publishAll(job));
        } catch (IOException e) {
            bot.say(job.chatId(), "%s downloaded, but it could not be published: %s."
                    .formatted(job.describe(), describe(e)));
        }
    }

    /** The answer to {@code /link}: the same hand-over again, without doing the work again. */
    public void resend(long chatId, Job job) throws IOException {
        bot.say(chatId, message(job, shortLinks.shareAll(job)));
    }

    /**
     * Hands over what the job produced: the file itself when it is small enough to travel, a link
     * when it is not.
     *
     * <p>The link is minted either way and is what the file falls back to — when it is too big, and
     * when Telegram refuses it for a reason no size check predicts. That is also what keeps
     * {@code /link} able to answer later about a job whose file went into the chat.
     */
    private void hand(Job job, List<ShortLink> links) {
        List<ShortLink> asLinks = new ArrayList<>();
        for (ShortLink link : links) {
            boolean sent = props.telegram().shouldSendFile(link.sizeBytes())
                    && bot.sendFile(job.chatId(), link.file(), caption(job, link, links.size()));
            if (!sent) {
                asLinks.add(link);
            }
        }
        if (!asLinks.isEmpty()) {
            bot.say(job.chatId(), message(job, asLinks));
        }
    }

    private String caption(Job job, ShortLink link, int total) {
        return total == 1
                ? "%s%n%s".formatted(job.describe(), Sizes.bytes(link.sizeBytes()))
                : "%s%n%s".formatted(link.fileName(), Sizes.bytes(link.sizeBytes()));
    }

    /**
     * One message however many files there are: a job was asked for once and is answered once, and
     * two cuts arriving as two notifications would read as two downloads.
     */
    private String message(Job job, List<ShortLink> links) {
        StringBuilder answer = new StringBuilder(job.describe());
        if (links.size() == 1) {
            ShortLink only = links.getFirst();
            answer.append(job.maxHeight() == null ? "" : " · " + job.maxHeight() + "p")
                    .append(System.lineSeparator()).append(shortLinks.urlOf(only))
                    .append(System.lineSeparator()).append(Sizes.bytes(only.sizeBytes()));
        } else {
            answer.append(" — ").append(links.size()).append(" files");
            for (int i = 0; i < links.size(); i++) {
                ShortLink link = links.get(i);
                answer.append(System.lineSeparator())
                        .append(i + 1).append(". ").append(shortLinks.urlOf(link))
                        .append("  ").append(Sizes.bytes(link.sizeBytes()));
            }
        }
        return answer.append(System.lineSeparator())
                .append("the link%s work%s for %d h".formatted(
                        links.size() == 1 ? "" : "s", links.size() == 1 ? "s" : "",
                        shortLinks.linkLifetime().toHours()))
                .toString();
    }

    private static String describe(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName() : e.getMessage();
    }
}
