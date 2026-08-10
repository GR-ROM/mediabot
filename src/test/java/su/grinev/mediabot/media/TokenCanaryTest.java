package su.grinev.mediabot.media;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import su.grinev.mediabot.AgentProperties;
import su.grinev.mediabot.Fixtures;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hourly look at whether the host is still answering.
 *
 * <p>The container's health check answers "is the provider running", which is not the question. One
 * that survives a change to what YouTube asks for keeps answering cheerfully and hands over a token
 * that is no longer accepted — and nothing short of a real request tells those apart.
 */
class TokenCanaryTest {

    private final List<Object> published = new ArrayList<>();
    private final ApplicationEventPublisher events = new ApplicationEventPublisher() {
        @Override
        public void publishEvent(Object event) {
            published.add(event);
        }

        @Override
        public void publishEvent(ApplicationEvent event) {
            published.add(event);
        }
    };

    /** A yt-dlp that answers however the test wants, without a binary or a network. */
    private static class Answering extends InMemoryYtDlp {
        private final IOException failure;

        Answering(IOException failure) {
            this.failure = failure;
        }

        @Override
        public MediaInfo probe(String url) throws IOException {
            if (failure != null) {
                throw failure;
            }
            return super.probe(url);
        }
    }

    private TokenCanary canaryOver(YtDlp ytDlp, AgentProperties props) {
        return new TokenCanary(ytDlp, events, props);
    }

    private static AgentProperties withProvider() {
        return Fixtures.builder().potProvider("http://potoken:4416", Path.of("/plugins")).build();
    }

    @Test
    void aHostThatAnswersIsWorthNoWordsAtAll() {
        canaryOver(new Answering(null), withProvider()).look();

        assertTrue(published.isEmpty(), "it exists to say one thing, at the moment it changes");
    }

    @Test
    void aRefusalIsReportedOnce() {
        TokenCanary canary = canaryOver(
                new Answering(new YtDlp.Failed(YtDlp.NEEDS_AUTHENTICATION)), withProvider());

        canary.look();
        canary.look();
        canary.look();

        assertEquals(1, published.size(), "still broken is not news; becoming broken is");
        assertTrue(((ProviderUnreachable) published.getFirst()).detail()
                .contains("not a bot"), published.toString());
    }

    @Test
    void answeringWithNoFormatsIsAFailureWearingASuccess() {
        // What a missing JS runtime looks like: yt-dlp exits zero and the video has nothing in it.
        InMemoryYtDlp silent = new InMemoryYtDlp();
        silent.publishing(new MediaInfo("Me at the zoo", "jawed", 19, false, List.of()));

        canaryOver(silent, withProvider()).look();

        assertEquals(1, published.size());
        assertTrue(((ProviderUnreachable) published.getFirst()).detail().contains("no formats"),
                published.toString());
    }

    @Test
    void aHostThatCannotBeReachedIsNotTheSameAsOneThatRefuses() {
        canaryOver(new Answering(new IOException("connection reset by peer")), withProvider()).look();

        // Waking somebody for a dropped connection is how an alert stops being read.
        assertTrue(published.isEmpty());
    }

    @Test
    void withNothingConfiguredThereIsNothingToWatch() {
        // No provider and no cookies is a decision somebody made; saying so hourly is noise.
        canaryOver(new Answering(new YtDlp.Failed(YtDlp.NEEDS_AUTHENTICATION)),
                Fixtures.props()).look();

        assertTrue(published.isEmpty());
    }

    @Test
    void comingBackIsQuietButLetsTheNextFailureBeHeard() {
        TokenCanary canary = canaryOver(
                new Answering(new YtDlp.Failed(YtDlp.NEEDS_AUTHENTICATION)), withProvider());
        canary.look();
        assertEquals(1, published.size());

        // Answering again, then refused again: the second refusal is worth a message of its own.
        canaryOver(new Answering(null), withProvider());
        TokenCanary recovered = canaryOver(new Answering(null), withProvider());
        recovered.look();

        assertEquals(1, published.size(), "recovery is a log line, not a message");
    }
}
