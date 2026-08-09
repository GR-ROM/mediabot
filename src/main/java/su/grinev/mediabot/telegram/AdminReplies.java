package su.grinev.mediabot.telegram;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import su.grinev.mediabot.access.AccessList;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The commands only the owner may run, and what the bot says when somebody else tries.
 *
 * <p>Apart from the dispatcher because these answer a different question. Everything there is about
 * a video; everything here is about who may ask for one, and the two share nothing but the chat
 * they arrive on.
 */
@Component
@Slf4j
public class AdminReplies {

    private final MediaBot bot;
    private final AccessList access;

    public AdminReplies(MediaBot bot, AccessList access) {
        this.bot = bot;
        this.access = access;
    }

    public void allow(long chatId, long who, String note) {
        if (!owns(chatId)) {
            return;
        }
        if (access.isOwner(who)) {
            bot.say(chatId, "Chat %d is an owner already.".formatted(who));
            return;
        }
        boolean added = access.allow(who, chatId, note);
        bot.say(chatId, added
                ? "Chat %d can use the bot now.%s".formatted(who,
                        note == null ? "" : " (" + note + ")")
                : "Chat %d could already use the bot.".formatted(who));
    }

    public void deny(long chatId, long who) {
        if (!owns(chatId)) {
            return;
        }
        if (access.isOwner(who)) {
            // Owners come from the configuration, so removing one here would report a success that
            // did not happen and leave the person still able to do everything.
            bot.say(chatId, ("Chat %d is an owner, and owners are set in the configuration rather "
                    + "than here. Edit mediabot.telegram.owners and restart.").formatted(who));
            return;
        }
        bot.say(chatId, access.deny(who)
                ? "Chat %d cannot use the bot any more.".formatted(who)
                : "Chat %d was not on the list.".formatted(who));
    }

    public void allowed(long chatId) {
        if (!owns(chatId)) {
            return;
        }
        StringBuilder answer = new StringBuilder("Owners: ");
        answer.append(access.owners().stream().map(String::valueOf)
                .collect(Collectors.joining(", ")));

        List<AccessList.Entry> entries = access.all();
        if (entries.isEmpty()) {
            answer.append(System.lineSeparator()).append("Nobody else has been let in.");
        } else {
            answer.append(System.lineSeparator())
                    .append("Allowed (%d):".formatted(entries.size()));
            entries.forEach(entry -> answer.append(System.lineSeparator())
                    .append("  ").append(entry.chatId())
                    .append(entry.note() == null ? "" : " — " + entry.note()));
        }
        bot.say(chatId, answer.toString());
    }

    /**
     * Refused rather than ignored, and only for somebody already let in.
     *
     * <p>A stranger never reaches here at all — the dispatcher drops them without a word, so the bot
     * gives away nothing to somebody probing it. Being told "that is not yours to do" is for the
     * people who are legitimately here and simply are not the owner.
     */
    private boolean owns(long chatId) {
        if (access.isOwner(chatId)) {
            return true;
        }
        bot.say(chatId, "That one is not yours to do.");
        log.warn("chat {} tried an owner command", chatId);
        return false;
    }
}
