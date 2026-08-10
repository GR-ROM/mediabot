package su.grinev.mediabot.telegram;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.meta.api.methods.send.SendAudio;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Putting a file in a chat, as the kind of thing it is.
 *
 * <p>Video and audio go as video and audio so they play where they land. When Telegram will not take
 * it as media — a codec it will not preview, a container it does not know — it is offered again as
 * a document rather than given up on, because a file that arrives unplayable is still the file that
 * was asked for.
 *
 * <p>A spy rather than a subclass: the library's {@code execute} overloads are final, so the only
 * seam is the mock maker. Everything below stubs exactly the one call that would reach the network.
 */
class MediaBotSendTest {

    private static final long CHAT = 600000002L;

    private final MediaBot bot = spy(new MediaBot(new DefaultBotOptions(), "test-token", "TestBot"));

    private MediaBot accepting() throws TelegramApiException {
        doReturn(new Message()).when(bot).execute(any(SendVideo.class));
        doReturn(new Message()).when(bot).execute(any(SendAudio.class));
        doReturn(new Message()).when(bot).execute(any(SendDocument.class));
        return bot;
    }

    private static Path file(Path directory, String name) throws IOException {
        Path path = directory.resolve(name);
        Files.write(path, "not really a video".getBytes());
        return path;
    }

    @Test
    void aVideoGoesAsAVideoSoItPlaysWhereItLands(@TempDir Path temp) throws Exception {
        accepting();

        assertTrue(bot.sendFile(CHAT, file(temp, "clip.mp4"), "here you go"));

        verify(bot).execute(any(SendVideo.class));
        verify(bot, never()).execute(any(SendDocument.class));
    }

    @Test
    void audioGoesAsAudio(@TempDir Path temp) throws Exception {
        accepting();

        assertTrue(bot.sendFile(CHAT, file(temp, "song.m4a"), ""));

        verify(bot).execute(any(SendAudio.class));
    }

    @Test
    void everyContainerThisBotProducesIsRecognisedAsVideo(@TempDir Path temp) throws Exception {
        accepting();

        for (String name : List.of("clip.mp4", "clip.mkv", "clip.mov", "clip.webm")) {
            assertTrue(bot.sendFile(CHAT, file(temp, name), ""), name);
        }

        verify(bot, org.mockito.Mockito.times(4)).execute(any(SendVideo.class));
        verify(bot, never()).execute(any(SendDocument.class));
    }

    @Test
    void somethingTelegramWillNotPreviewIsStillDelivered(@TempDir Path temp) throws Exception {
        accepting();
        doThrow(new TelegramApiException("unsupported video format"))
                .when(bot).execute(any(SendVideo.class));

        assertTrue(bot.sendFile(CHAT, file(temp, "clip.mp4"), ""));

        verify(bot).execute(any(SendDocument.class));
    }

    @Test
    void whenNothingWillGoThroughTheCallerIsToldSoItCanFallBackToTheLink(@TempDir Path temp)
            throws Exception {

        accepting();
        doThrow(new TelegramApiException("too large")).when(bot).execute(any(SendVideo.class));
        doThrow(new TelegramApiException("too large")).when(bot).execute(any(SendDocument.class));

        // Saying it arrived when it did not is how somebody waits for nothing.
        assertFalse(bot.sendFile(CHAT, file(temp, "clip.mp4"), ""));
    }

    @Test
    void anythingElseGoesStraightAsADocument(@TempDir Path temp) throws Exception {
        accepting();

        assertTrue(bot.sendFile(CHAT, file(temp, "notes.txt"), ""));

        verify(bot).execute(any(SendDocument.class));
        verify(bot, never()).execute(any(SendVideo.class));
    }
}
