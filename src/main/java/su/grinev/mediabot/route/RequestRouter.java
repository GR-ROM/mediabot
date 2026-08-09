package su.grinev.mediabot.route;

import org.springframework.stereotype.Component;
import su.grinev.mediabot.graph.Pipeline;
import su.grinev.mediabot.graph.PipelineText;
import su.grinev.mediabot.media.Container;
import su.grinev.mediabot.media.Quality;
import su.grinev.mediabot.jobs.JobSpec;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a message without asking a model anything.
 *
 * <p>Every command has two spellings — {@code /audio mp3 <url>} and "grab the sound from &lt;url&gt;
 * as mp3" — and they meet here: the name is taken from the slash or from the words, and the
 * arguments are then pulled out of whatever is left by the same regexes either way. That is why
 * argument order is free, and why a phrase never gets an option the slash form does not have.
 *
 * <p>It is deliberately a grammar rather than a judgement. What it does not recognise it says it
 * does not recognise — a wrong guess here is a download nobody asked for, and there is a parser
 * behind this for the sentences worth a second opinion.
 */
@Component
public class RequestRouter {

    private static final Pattern URL = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);




    /**
     * @return what the message asks for, or empty when it cannot be read literally
     */
    public Optional<Request> parse(long chatId, String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String trimmed = text.strip();

        // More than one step, so it describes a graph rather than picks one of the plain shapes.
        // Read strictly: a chain that does not parse is an error with a word quoted back, never a
        // download of whatever could be salvaged from it.
        if (PipelineText.isChain(trimmed)) {
            return Optional.of(new Request.Fetch(
                    JobSpec.of(chatId, PipelineText.read(trimmed))));
        }

        Command named = null;
        String rest = trimmed;

        if (trimmed.startsWith("/")) {
            String[] parts = trimmed.split("\\s+", 2);
            Optional<Command> found = Command.bySlashName(parts[0].substring(1));
            if (found.isEmpty()) {
                return Optional.empty();
            }
            named = found.get();
            rest = parts.length > 1 ? parts[1] : "";
        }

        Matcher found = URL.matcher(rest);
        boolean hasUrl = found.find();
        String url = hasUrl ? trimTrailingPunctuation(found.group()) : null;
        String words = (hasUrl
                ? rest.substring(0, found.start()) + " " + rest.substring(found.end())
                : rest).toLowerCase(Locale.ROOT).strip();

        Command command = named != null ? named : infer(words, url);
        if (command == null) {
            return Optional.empty();
        }
        // A command that was named and cannot be served says so in its own words. Only a message
        // that names nothing at all comes back empty, and that is what the model is for.
        return Optional.of(command.read(new Args(chatId, url, words)));
    }

    /**
     * Whether the message says anything this grammar did not use.
     *
     * <p>Asked after a message has already been read as a plain download, to decide whether it is
     * worth a second opinion. A bare link leaves nothing over and neither does "&lt;link&gt; 720p",
     * so the common paths never pay for one; "even out the volume" does, because there is no way to
     * say that here.
     *
     * <p>A named command is never leftover by definition — somebody who writes {@code /download}
     * has said what they want.
     */
    public boolean hasWordsItDidNotUse(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String rest = text.strip();
        if (rest.startsWith("/")) {
            return false;
        }
        Matcher found = URL.matcher(rest);
        if (!found.find()) {
            return false;
        }
        String words = (rest.substring(0, found.start()) + " " + rest.substring(found.end()))
                .toLowerCase(Locale.ROOT);
        return !Args.withoutKnownArguments(words).isBlank();
    }

    private static Command infer(String words, String url) {
        if (url == null) {
            return Command.spokenWithoutUrl(words).orElse(null);
        }
        return Command.spokenWithUrl(words)
                .orElseGet(() -> url.contains("list=") ? Command.PLAYLIST : Command.DOWNLOAD);
    }

    /**
     * A link at the end of a sentence collects the full stop, and a link in brackets collects the
     * bracket. Both make yt-dlp fail with something that reads like the video being gone.
     */
    private static String trimTrailingPunctuation(String url) {
        int end = url.length();
        while (end > 0 && ".,;:!?)]}>\"'«»".indexOf(url.charAt(end - 1)) >= 0) {
            end--;
        }
        return url.substring(0, end);
    }
}
