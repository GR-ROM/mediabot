package su.grinev.mediabot.console;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import su.grinev.mediabot.jobs.Job;
import su.grinev.mediabot.jobs.JobQueue;
import su.grinev.mediabot.llm.ModelPreflight;
import su.grinev.mediabot.telegram.MediaBot;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

@Component
@Profile("console")
@Slf4j
public class ConsoleRunner implements ApplicationRunner {

    private final ConsoleBot bot;
    private final JobQueue queue;
    private final ModelPreflight preflight;
    private final ConfigurableApplicationContext context;
    private final long chatId;

    public ConsoleRunner(MediaBot bot, JobQueue queue, ModelPreflight preflight,
                         ConfigurableApplicationContext context,
                         @Value("${mediabot.console.chat-id:1}") long chatId) {
        this.bot = (ConsoleBot) bot;
        this.queue = queue;
        this.preflight = preflight;
        this.context = context;
        this.chatId = chatId;
    }

    @Override
    public void run(ApplicationArguments args) {
        var check = preflight.check();
        check.warnings().forEach(warning -> log.warn("{}", warning));
        if (!check.usable()) {
            check.problems().forEach(problem -> log.error("{}", problem));
            log.error("Continuing: the router reads messages on its own, so this only costs the "
                    + "unusual phrasings, which get the help text until the host is back.");
        }

        List<Job> interrupted = queue.takeInterrupted();
        interrupted.forEach(job -> System.out.printf("[bot] job %d (%s) was interrupted by a "
                + "restart and is not running any more%n", job.id(), job.describe()));
        queue.sweep();
    }

    @EventListener(ApplicationReadyEvent.class)
    void startReading() {
        Thread.ofPlatform().name("console").daemon(true).start(() -> {
            loop();
            context.close();
        });
    }

    private void loop() {
        banner();
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.printf("%n> ");
            System.out.flush();
            String line;
            try {
                line = input.readLine();
            } catch (Exception e) {
                log.warn("console input failed: {}", e.toString());
                break;
            }
            if (line == null || line.strip().equalsIgnoreCase("/quit")) {
                break;
            }
            String text = line.strip();
            if (text.isEmpty()) {
                continue;
            }
            try {
                bot.deliver(chatId, text);
            } catch (RuntimeException e) {
                log.error("handling that failed", e);
            }
        }
        System.out.println("bye");
        System.out.flush();
    }


    private void useUtf8Console() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                || !System.out.charset().equals(StandardCharsets.UTF_8)) {
            return;
        }
        try {
            new ProcessBuilder("cmd", "/c", "chcp", "65001")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("could not switch the console to UTF-8: {}", e.toString());
        }
    }

    private void banner() {
        useUtf8Console();
        System.out.printf("%n=== mediabot console, chat %d ===%n", chatId);
        System.out.println("send a link, or say what you want — /download, /audio, /transcode,");
        System.out.println("/playlist, /status, /link, /cancel, /help, all also in plain English");
        System.out.println("/help  the two spellings side by side       /quit  leave");
        System.out.flush();
    }
}
