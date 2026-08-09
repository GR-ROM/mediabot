package su.grinev.mediabot.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import su.grinev.mediabot.graph.Graph;
import su.grinev.mediabot.graph.PipelineText;
import su.grinev.mediabot.jobs.JobSpec;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The model's entire remaining job: turn one sentence into one line of pipeline text, or say it
 * cannot.
 *
 * <p>It has no tools, sees no history, and never writes a word the user reads. What comes back is
 * handed to {@link PipelineText}, the same reader the slash form goes through, so the model cannot
 * name a step that does not exist or give one an argument it does not take — a wrong answer stops at
 * the grammar rather than becoming a job. That is the whole reason it answers in this language
 * instead of in JSON of its own shape: there is one validator, and the model is behind it.
 *
 * <p>It is not shown the link either. It writes {@code <url>} and the address is put in from the
 * message it was reading, which is the one thing it used to get wrong that no amount of validation
 * could catch: an invented link is a perfectly well-formed link.
 *
 * <p>Reached only for messages {@code RequestRouter} could not read literally. When the model host
 * is down this returns empty and the user gets the help text, which is a worse answer than a parse
 * and a much better one than a confident invention.
 */
@Component
@Slf4j
public class IntentParser {

    private static final String SYSTEM = """
            You turn one message into one line of pipeline text and nothing else. No prose, no
            explanation, no code fences.

            A line is a chain of steps, each starting with a slash:

              /download <url> [height]     always first, always exactly <url>
              /cut 1:00-1:15 [2:40-3:00]   one range per piece; two ranges make two files
              /encode [mp4|mkv|webm] [height]
              /normalize                   even out the loudness
              /audio [mp3|m4a|opus]        the sound alone
              /join                        put the pieces back into one file

            Rules:
            - Write <url> exactly like that. Never copy a link and never invent one: the
              address is filled in for you.
            - A height is one of 144, 240, 360, 480, 720, 1080, 1440, 2160, written like
              720p, or 4k. Leave it out when the message does not ask for one.
            - A timecode is m:ss or h:mm:ss.
            - Use only the steps listed. If the message asks for something else, or you are
              not sure what it asks for, answer exactly: unclear
            - A wrong guess costs somebody a download they did not want.

            Examples:
            get me this                          -> /download <url>
            just the sound as mp3                -> /download <url> /audio mp3
            too big, shrink it to 480p           -> /download <url> /encode 480p
            keep 1:00 to 1:15 and 2:40 to 3:00,
            both at 720p, even out the volume    -> /download <url> /cut 1:00-1:15 2:40-3:00 \
            /encode 720p /normalize
            is this any good?                    -> unclear
            """;

    private static final Pattern URL = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

    private final LlmClient llm;
    private final LlmAvailability availability;
    private final ObjectMapper mapper;

    public IntentParser(LlmClient llm, LlmAvailability availability, ObjectMapper mapper) {
        this.llm = llm;
        this.availability = availability;
        this.mapper = mapper;
    }

    /**
     * @return what the message asks for, or empty when the model could not tell, would not answer,
     *     or answered something this bot cannot serve
     */
    public Optional<JobSpec> parse(long chatId, String text) {
        Optional<String> link = urlIn(text);
        if (link.isEmpty() || !availability.shouldTry()) {
            return Optional.empty();
        }
        try {
            var completion = llm.complete(List.of(
                    message("system", SYSTEM),
                    message("user", text)), List.of());
            availability.succeeded();
            return specOf(chatId, completion.content(), link.get());
        } catch (java.io.IOException e) {
            availability.failed(e);
            log.debug("the parser could not be reached: {}", e.toString());
            return Optional.empty();
        } catch (Exception e) {
            log.debug("the parser failed on {}: {}", text, e.toString());
            return Optional.empty();
        }
    }

    private Optional<JobSpec> specOf(long chatId, String answer, String url) {
        Optional<String> written = pipelineIn(answer);
        if (written.isEmpty()) {
            log.debug("the parser did not answer with a pipeline: {}", answer);
            return Optional.empty();
        }
        String pipeline = withRealUrl(written.get(), url);
        try {
            Graph graph = PipelineText.read(pipeline);
            log.debug("the parser read that as {}", pipeline);
            return Optional.of(JobSpec.of(chatId, graph));
        } catch (IllegalArgumentException e) {
            log.debug("the parser answered something that is not a pipeline ({}): {}",
                    e.getMessage(), pipeline);
            return Optional.empty();
        }
    }

    /**
     * A small model puts the line in a sentence, or in backticks, however plainly it is told not to,
     * so the chain is cut out of whatever came back rather than the answer being refused for its
     * edges. Everything before the first {@code /download} is prose and everything after the line
     * ends is the model carrying on talking.
     */
    private static Optional<String> pipelineIn(String answer) {
        if (answer == null) {
            return Optional.empty();
        }
        int start = answer.indexOf("/download");
        if (start < 0) {
            return Optional.empty();
        }
        String line = answer.substring(start);
        int end = line.indexOf('\n');
        if (end >= 0) {
            line = line.substring(0, end);
        }
        return Optional.of(line.replace("`", "").strip());
    }

    private static String withRealUrl(String pipeline, String url) {
        String replacement = Matcher.quoteReplacement(url);
        return URL.matcher(pipeline.replace("<url>", url))
                .replaceAll(replacement);
    }

    private static Optional<String> urlIn(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher found = URL.matcher(text);
        return found.find() ? Optional.of(trimTrailingPunctuation(found.group())) : Optional.empty();
    }

    private static String trimTrailingPunctuation(String url) {
        int end = url.length();
        while (end > 0 && ".,;:!?)]}>\"'«»".indexOf(url.charAt(end - 1)) >= 0) {
            end--;
        }
        return url.substring(0, end);
    }

    private ObjectNode message(String role, String content) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", role);
        node.put("content", content);
        return node;
    }
}
