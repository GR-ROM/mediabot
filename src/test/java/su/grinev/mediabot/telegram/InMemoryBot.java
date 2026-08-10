package su.grinev.mediabot.telegram;

import org.telegram.telegrambots.bots.DefaultBotOptions;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A chat that remembers what was said to it.
 *
 * <p>An implementation rather than a mock, because the transport has behaviour a stub does not: a
 * message comes back with an id, the same id is what an edit rewrites, and a refused file is
 * {@code false} rather than an exception. Every one of those is something a caller branches on, and
 * a mock returning null for an unstubbed call turns each of them into a different bug — usually a
 * {@code NullPointerException} two layers away from the test that caused it.
 *
 * <p>Nothing here reaches the network: the library's send methods are never called.
 */
public class InMemoryBot extends MediaBot {

    /** One message as it stands now, after however many edits. */
    public record Sent(long chatId, int messageId, String text, int edits) {}

    /** One file handed over, as the chat would have received it. */
    public record File(long chatId, Path path, String caption) {}

    private final List<Sent> messages = new ArrayList<>();
    private final List<File> files = new ArrayList<>();

    /** Somebody who blocked the bot: nothing sends, and nothing comes back to edit. */
    private boolean muted;

    /** What Telegram does for a codec it will not preview, or anything over its own limit. */
    private boolean refusingFiles;

    public InMemoryBot() {
        super(new DefaultBotOptions(), "test-token", "TestBot");
    }

    public void mute() {
        this.muted = true;
    }

    public void refuseFiles() {
        this.refusingFiles = true;
    }

    @Override
    public Optional<Integer> say(long chatId, String text) {
        if (muted) {
            return Optional.empty();
        }
        int messageId = messages.size() + 1;
        messages.add(new Sent(chatId, messageId, text, 0));
        return Optional.of(messageId);
    }

    @Override
    public void edit(long chatId, int messageId, String text) {
        for (int i = 0; i < messages.size(); i++) {
            Sent existing = messages.get(i);
            if (existing.messageId() == messageId) {
                messages.set(i, new Sent(chatId, messageId, text, existing.edits() + 1));
                return;
            }
        }
        // Editing a message that was never sent is an error from Telegram and a bug here: the only
        // way to reach it is to have kept an id that never existed.
        throw new IllegalStateException("edited message " + messageId + ", which was never sent");
    }

    @Override
    public boolean sendFile(long chatId, Path file, String caption) {
        if (refusingFiles) {
            return false;
        }
        files.add(new File(chatId, file, caption));
        return true;
    }

    // ---------------------------------------------------------------- what a test asks it

    public List<Sent> messages() {
        return List.copyOf(messages);
    }

    public List<File> files() {
        return List.copyOf(files);
    }

    public boolean saidNothing() {
        return messages.isEmpty() && files.isEmpty();
    }

    /** The last thing this chat was told, which is the answer to whatever was just done. */
    public String said() {
        if (messages.isEmpty()) {
            throw new AssertionError("nothing was said");
        }
        return messages.getLast().text();
    }

    /** The text of one message as it stands, edits included. */
    public String textOf(int messageId) {
        return messages.stream()
                .filter(sent -> sent.messageId() == messageId)
                .map(Sent::text)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no message " + messageId));
    }

    public int editsTo(int messageId) {
        return messages.stream()
                .filter(sent -> sent.messageId() == messageId)
                .mapToInt(Sent::edits)
                .findFirst()
                .orElse(0);
    }
}
