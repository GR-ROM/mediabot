package su.grinev.mediabot.telegram;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.generics.BotSession;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import su.grinev.mediabot.AgentProperties;
import su.grinev.mediabot.jobs.Job;
import su.grinev.mediabot.jobs.JobQueue;
import su.grinev.mediabot.llm.ModelPreflight;

import java.util.List;

/**
 * Everything that has to happen once, in the order it has to happen in.
 *
 * <p>The order is the content. Preflight before going online, so a misconfigured model host is a
 * message on the console rather than a chat that answers nothing. Interrupted jobs announced after
 * going online, because announcing them needs a bot to announce them with — a restart that silently
 * drops somebody's download is the failure this whole arrangement exists to prevent.
 */
@Component
@Profile("!console")
@RequiredArgsConstructor
@Slf4j
public class BotRegistration implements ApplicationRunner {

    private final MediaBot bot;
    private final JobQueue queue;
    private final ModelPreflight preflight;
    private final AgentProperties props;

    // Referenced so Spring builds it, and building it is what wires the bot's message handler.
    private final ChatDispatcher dispatcher;

    private BotSession session;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        var check = preflight.check();
        check.warnings().forEach(warning -> log.warn("{}", warning));
        if (!check.usable()) {
            // Not fatal, and barely even degrading: the router reads a link, a height and the
            // commands without asking anything. Only an unusual phrasing needs the parser.
            check.problems().forEach(problem -> log.error("{}", problem));
            log.error("Starting anyway: the router reads messages on its own, so this only costs "
                    + "the unusual phrasings, which get the help text until the host is back.");
        }

        if (!props.telegram().hasToken()) {
            log.error("No mediabot.telegram.token — the bot will not go online. "
                    + "Set MEDIABOT_TG_TOKEN.");
            return;
        }

        try {
            session = register();
        } catch (Exception e) {
            // Not fatal, for the same reason a dead model host is not: everything that does not
            // need Telegram still works. The link server is up, so a file already published is
            // still being served, and the queue keeps its rows — which is what makes the next
            // start able to say what was lost.
            log.error("the bot did not go online: {}", reason(e));
            log.error("Starting anyway: the link server and the queue do not need Telegram. "
                    + "Nothing will be read from chat until the token works and this is restarted.");
            queue.sweep();
            return;
        }
        log.info("bot online as @{}", bot.getBotUsername());

        announceInterrupted();
        queue.sweep();
    }

    /** The one line that talks to Telegram, apart so a test can make it fail. */
    BotSession register() throws Exception {
        return new TelegramBotsApi(DefaultBotSession.class).registerBot(bot);
    }

    /**
     * The whole chain, because the outermost message here is famously "Error removing old webhook"
     * and the sentence that says what actually went wrong — a 401, a proxy, a refused connection —
     * is two causes further down.
     */
    private static String reason(Throwable e) {
        StringBuilder chain = new StringBuilder();
        for (Throwable at = e; at != null; at = at.getCause()) {
            String message = at.getMessage();
            chain.append(chain.isEmpty() ? "" : " <- ")
                    .append(message == null || message.isBlank()
                            ? at.getClass().getSimpleName()
                            : at.getClass().getSimpleName() + ": " + message);
            if (at.getCause() == at) {
                break;
            }
        }
        return chain.toString();
    }

    /**
     * Tells people what the restart destroyed.
     *
     * <p>Marked rather than resumed, and said rather than swallowed. An hour after a crash the video
     * is usually no longer wanted, so re-downloading it unasked spends real bandwidth on a guess —
     * but leaving somebody to wait forever for a file nothing is producing is worse than either.
     */
    private void announceInterrupted() {
        List<Job> interrupted = queue.takeInterrupted();
        for (Job job : interrupted) {
            bot.say(job.chatId(), ("Job %d (%s) was still downloading when the bot restarted, so it "
                    + "did not finish. Send the link again if you still want it.")
                    .formatted(job.id(), job.describe()));
        }
        if (!interrupted.isEmpty()) {
            log.info("announced {} interrupted job(s)", interrupted.size());
        }
    }

    @PreDestroy
    void stop() {
        bot.shutdown();
        if (session != null) {
            session.stop();
        }
    }
}
