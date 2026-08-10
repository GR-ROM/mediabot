package su.grinev.mediabot.media;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import su.grinev.mediabot.AgentProperties;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Asks the host one question an hour, so a broken provider is found before somebody else finds it.
 *
 * <p>The container's health check answers "is it running", which is not the question. A provider
 * that has died says so; a provider that survives a change to what YouTube asks for keeps answering
 * cheerfully and hands over a token that is no longer accepted. Nothing short of a real request can
 * tell those apart, so this makes one.
 *
 * <p>A probe and not a download: metadata for one short, famously stable video, which costs the host
 * nothing and this bot nothing. Hourly rather than often, because a canary that is itself rate
 * limited proves the wrong thing.
 *
 * <p>Silent while it works. It exists to produce exactly one message, at the moment the answer
 * changes, and to say nothing whatsoever the rest of the time.
 */
@Component
@Slf4j
public class TokenCanary {

    private final YtDlp ytDlp;
    private final ApplicationEventPublisher events;
    private final AgentProperties props;

    /** True while the last look said the host was refusing, so recovery is worth a line too. */
    private volatile boolean refusing;

    public TokenCanary(YtDlp ytDlp, ApplicationEventPublisher events, AgentProperties props) {
        this.ytDlp = ytDlp;
        this.events = events;
        this.props = props;
    }

    @Scheduled(fixedDelay = 1, initialDelay = 10, timeUnit = TimeUnit.HOURS)
    public void look() {
        if (!props.media().hasPotProvider() && !props.media().hasCookies()) {
            // Nothing configured to break. The bot already said so once at startup and repeating it
            // hourly would be noise about a decision somebody made on purpose.
            return;
        }
        try {
            MediaInfo info = ytDlp.probe(props.media().canaryUrl());
            if (info.formats().isEmpty()) {
                // Answered, with nothing in it. That is what a missing JS runtime looks like, and
                // it is a failure wearing a success.
                report("the host answered with no formats at all");
                return;
            }
            recovered();

        } catch (YtDlp.Failed e) {
            report(e.getMessage());
        } catch (IOException e) {
            // The host being unreachable is not the same as the host refusing, and waking somebody
            // for a dropped connection is how an alert stops being read.
            log.debug("the canary could not reach the host: {}", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void report(String reason) {
        if (refusing) {
            log.debug("the canary is still being refused: {}", reason);
            return;
        }
        refusing = true;
        log.warn("the canary was refused: {}", reason);
        events.publishEvent(new ProviderUnreachable(reason));
    }

    private void recovered() {
        if (refusing) {
            refusing = false;
            log.info("the canary is being answered again");
        }
    }
}
