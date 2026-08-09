package su.grinev.mediabot.telegram;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.generics.BotSession;
import su.grinev.mediabot.AgentProperties;
import su.grinev.mediabot.jobs.Job;
import su.grinev.mediabot.jobs.JobQueue;
import su.grinev.mediabot.llm.ModelPreflight;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a bot that cannot reach Telegram is still allowed to be.
 *
 * <p>Going online is one of the things this process does, not the reason it exists: the link server
 * is still serving files that were published before, and the queue still holds the rows that say
 * what was promised. Killing the process over a bad token throws all of that away and leaves a log
 * that says nothing about why.
 */
class BotRegistrationTest {

    private MediaBot bot;
    private JobQueue queue;
    private ModelPreflight preflight;
    private AgentProperties props;

    @BeforeEach
    void setUp() {
        bot = mock(MediaBot.class);
        queue = mock(JobQueue.class);
        preflight = mock(ModelPreflight.class);
        props = mock(AgentProperties.class);

        when(preflight.check()).thenReturn(new ModelPreflight.Result(true, List.of(), List.of()));
        when(props.telegram()).thenReturn(new AgentProperties.Telegram(
                "123:token", "bot", "https://api.telegram.org", 1024, 0, List.of(), List.of()));
        when(queue.takeInterrupted()).thenReturn(List.of(mock(Job.class)));
    }

    @Test
    void aTokenTelegramRefusesDoesNotTakeTheProcessWithIt() {
        BotRegistration registration = registrationThat(() -> {
            throw new IllegalStateException("[401] Unauthorized");
        });

        assertDoesNotThrow(() -> registration.run(null));
        verify(queue).sweep();
    }

    @Test
    void nothingIsAnnouncedToAChatThatCannotBeReached() {
        BotRegistration registration = registrationThat(() -> {
            throw new IllegalStateException("[401] Unauthorized");
        });

        assertDoesNotThrow(() -> registration.run(null));

        verify(bot, never()).say(anyLong(), any());
        verify(queue, never()).takeInterrupted();
    }

    @Test
    void goingOnlineStillAnnouncesWhatTheRestartDestroyed() {
        BotRegistration registration = registrationThat(() -> mock(BotSession.class));

        assertDoesNotThrow(() -> registration.run(null));

        verify(queue).takeInterrupted();
        verify(bot).say(anyLong(), any());
        verify(queue).sweep();
    }

    private interface Registration {
        BotSession attempt() throws Exception;
    }

    /**
     * The seam: everything but the one call that talks to Telegram is the real class, so what is
     * under test is the order things happen in and what survives a failure.
     */
    private BotRegistration registrationThat(Registration attempt) {
        return new BotRegistration(bot, queue, preflight, props, mock(ChatDispatcher.class)) {
            @Override
            BotSession register() throws Exception {
                return attempt.attempt();
            }
        };
    }
}
