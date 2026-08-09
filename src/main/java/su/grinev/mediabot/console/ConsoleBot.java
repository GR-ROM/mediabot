package su.grinev.mediabot.console;

import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import su.grinev.mediabot.telegram.MediaBot;
import su.grinev.mediabot.text.Sizes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

@Slf4j
public class ConsoleBot extends MediaBot {

    private static final int PROGRESS_LINE_CHARS = 90;

    private final AtomicInteger messageIds = new AtomicInteger();
    private volatile BiConsumer<Long, String> messages = (chatId, text) -> { };
    private volatile String lastEdit;


    public ConsoleBot() {
        super(new DefaultBotOptions(), "", "console");
    }

    @Override
    public void handleWith(BiConsumer<Long, String> handler) {
        super.handleWith(handler);
        this.messages = handler;
    }


    public void deliver(long chatId, String text) {
        messages.accept(chatId, text);
    }


    @Override
    public Optional<Integer> say(long chatId, String text) {
        int id = messageIds.incrementAndGet();
        print("bot", ConsoleText.plain(text));
        return Optional.of(id);
    }

    @Override
    public boolean sendFile(long chatId, Path file, String caption) {
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            size = 0;
        }
        print("bot", "[file] %s (%s)%s".formatted(file.getFileName(), Sizes.bytes(size),
                caption == null || caption.isBlank() ? "" : " — " + ConsoleText.plain(caption)));
        return true;
    }

    @Override
    public void edit(long chatId, int messageId, String text) {
        String line = ConsoleText.shorten(text, PROGRESS_LINE_CHARS);
        if (line.equals(lastEdit)) {
            return;
        }
        lastEdit = line;
        System.out.printf("       %s%n", line);
        System.out.flush();
    }



    private void print(String tag, String text) {
        lastEdit = null;
        System.out.printf("%n[%s] %s%n", tag, text);
        System.out.flush();
    }
}
