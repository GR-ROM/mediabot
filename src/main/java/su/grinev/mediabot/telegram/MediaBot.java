package su.grinev.mediabot.telegram;

import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.api.methods.send.SendAudio;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

/**
 * The chat transport, and nothing else.
 *
 * <p>Long polling handed straight to a worker: {@code onUpdateReceived} runs on the polling thread,
 * and anything slow done there — a probe, a model round — stops the bot receiving anything at all,
 * including from other people.
 */
@Slf4j
public class MediaBot extends TelegramLongPollingBot {

    private final String username;
    private final ExecutorService workers =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("chat-", 1).factory());

    /** Set once the dispatcher exists; the two would otherwise depend on each other in a circle. */
    private volatile BiConsumer<Long, String> onMessage = (chatId, text) -> { };

    /** Set by whoever is waiting on a button; the payload is whatever it put on the button. */

    public MediaBot(DefaultBotOptions options, String token, String username) {
        super(options, token);
        this.username = username;
    }

    public void handleWith(BiConsumer<Long, String> handler) {
        this.onMessage = handler;
    }


    @Override
    public String getBotUsername() {
        return username;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }
        long chatId = update.getMessage().getChatId();
        String text = update.getMessage().getText().strip();
        workers.submit(() -> {
            try {
                onMessage.accept(chatId, text);
            } catch (RuntimeException e) {
                log.error("handling a message from chat {} failed", chatId, e);
                say(chatId, "Something went wrong handling that. Try again.");
            }
        });
    }


    /** @return the id of the message sent, for editing it later */
    public Optional<Integer> say(long chatId, String text) {
        try {
            var sent = execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .disableWebPagePreview(true)
                    .build());
            return Optional.of(sent.getMessageId());
        } catch (TelegramApiException e) {
            log.warn("could not send to chat {}: {}", chatId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Puts the file in the chat, as the kind of thing it is.
     *
     * <p>Video and audio go as video and audio so they play where they land; anything else goes as a
     * document. When Telegram will not take it as media — a codec it will not preview, a container
     * it does not know — it is offered again as a document rather than given up on, because a file
     * that arrives unplayable is still the file that was asked for.
     *
     * @return whether it arrived; false means the caller should fall back to the link
     */
    public boolean sendFile(long chatId, Path file, String caption) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        InputFile payload = new InputFile(file.toFile());
        try {
            if (VIDEO.stream().anyMatch(name::endsWith)) {
                execute(SendVideo.builder().chatId(chatId).video(payload).caption(caption)
                        .supportsStreaming(true).build());
                return true;
            }
            if (AUDIO.stream().anyMatch(name::endsWith)) {
                execute(SendAudio.builder().chatId(chatId).audio(payload).caption(caption).build());
                return true;
            }
        } catch (TelegramApiException e) {
            log.info("chat {} would not take {} as media ({}), trying it as a document",
                    chatId, file.getFileName(), e.getMessage());
        }
        try {
            execute(SendDocument.builder().chatId(chatId)
                    .document(new InputFile(file.toFile())).caption(caption).build());
            return true;
        } catch (TelegramApiException e) {
            log.warn("could not send {} to chat {}: {}", file.getFileName(), chatId, e.getMessage());
            return false;
        }
    }

    private static final List<String> VIDEO = List.of(".mp4", ".mkv", ".mov", ".webm");

    private static final List<String> AUDIO = List.of(".mp3", ".m4a", ".opus", ".ogg", ".wav");

    /** Rewrites an earlier message in place — how progress is shown without flooding the chat. */
    public void edit(long chatId, int messageId, String text) {
        try {
            execute(EditMessageText.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .text(text)
                    .disableWebPagePreview(true)
                    .build());
        } catch (TelegramApiException e) {
            // Editing to identical text is an error from Telegram's side and means nothing here.
            log.debug("could not edit message {} in chat {}: {}", messageId, chatId, e.getMessage());
        }
    }

    public void shutdown() {
        workers.shutdown();
    }
}
