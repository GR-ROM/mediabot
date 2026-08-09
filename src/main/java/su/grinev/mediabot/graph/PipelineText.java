package su.grinev.mediabot.graph;

import su.grinev.mediabot.media.AudioFormat;
import su.grinev.mediabot.media.Container;
import su.grinev.mediabot.media.Quality;

import su.grinev.mediabot.media.Trim;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The slash form of a pipeline, read into {@link Pipeline} calls.
 *
 * <p>{@code /download <url> /cut 1:00-2:00 2:00-2:20 /encode mp4 720p /normalize} — the slash is the
 * separator, so there is no joining word to spell and no prose around the arguments. Strict on
 * purpose: every token after a verb has exactly one meaning, and one it does not have stops the
 * whole thing with the word quoted back. There is no vocabulary of filler here to grow stale, which
 * is the reason this is not a grammar over English.
 */
public final class PipelineText {

    private static final Pattern SEGMENTS = Pattern.compile("(?<=\\s)/");

    private static final Pattern URL = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

    private static final Pattern HEIGHT = Pattern.compile("^(\\d{2,4})[pп]?$");

    /** Below this it is not a video, above it nothing publishes; and x264 needs an even height. */
    private static final int MIN_HEIGHT = 96;

    private static final int MAX_HEIGHT = 4320;

    /**
     * What each step is called, what it takes and how it is written.
     *
     * <p>Kept as one table because every refusal quotes from it: a step that says only what was
     * wrong leaves somebody guessing what would have been right, and this is the whole reason the
     * steps are spelled out rather than described in prose.
     */
    private record Step(String name, String takes, String form) {

        String usage() {
            return "/%s takes %s: %s".formatted(name, takes, form);
        }
    }

    private static final List<Step> STEPS = List.of(
            new Step("download", "a link, and a height if you want one",
                    "/download <link> [720p]"),
            new Step("cut", "one range per piece",
                    "/cut 1:00-2:00 [2:40-3:00 ...]"),
            new Step("encode", "a profile, a container, a height, or any of them",
                    "/encode [" + Quality.spelled() + "] [" + Container.spelled() + "] [720p]"),
            new Step("normalize", "nothing", "/normalize"),
            new Step("audio", "a format, m4a if you leave it out",
                    "/audio [" + AudioFormat.spelled() + "]"),
            new Step("join", "nothing", "/join"),
            new Step("publish", "nothing", "/publish"));

    private PipelineText() {
    }

    private static String usage(String verb) {
        return STEPS.stream()
                .filter(step -> step.name().equals(verb))
                .findFirst()
                .map(Step::usage)
                .orElseGet(PipelineText::allSteps);
    }

    private static String allSteps() {
        return "the steps are " + STEPS.stream().map(step -> "/" + step.name())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static Pipeline.Invalid refuse(String verb, String problem) {
        return new Pipeline.Invalid(problem + " — " + usage(verb));
    }

    /** Whether this is a chain of steps rather than one of the plain one-step commands. */
    public static boolean isChain(String text) {
        return text != null && text.strip().startsWith("/") && SEGMENTS.matcher(text.strip()).find();
    }

    public static Graph read(String text) {
        if (text == null || text.isBlank()) {
            throw new Pipeline.Invalid("there is nothing here to do");
        }
        String[] segments = SEGMENTS.split(text.strip());
        Pipeline pipeline = null;
        for (String segment : segments) {
            String[] words = strip(segment);
            if (words.length == 0) {
                throw new Pipeline.Invalid("there is a stray / with no step after it — " + allSteps());
            }
            String verb = words[0].toLowerCase(Locale.ROOT);
            List<String> args = new ArrayList<>(List.of(words).subList(1, words.length));
            pipeline = step(pipeline, verb, args, segment.strip());
        }
        return pipeline.build();
    }

    private static Pipeline step(Pipeline pipeline, String verb, List<String> args, String segment) {
        if (verb.equals("download")) {
            if (pipeline != null) {
                throw refuse("download", "the download is the first step or it is nowhere");
            }
            return download(args, segment);
        }
        if (pipeline == null) {
            throw refuse("download", "a pipeline starts with the thing to work on, not with /" + verb);
        }
        return switch (verb) {
            case "cut", "trim" -> pipeline.cut(windows(args));
            // The same operation is called /transcode when it is a command on its own. It having
            // two names by accident of when each was written is not a distinction anybody should
            // have to learn, so both work in both places.
            case "encode", "transcode", "compress", "convert" -> encode(pipeline, args);
            case "normalize" -> {
                reject(args, "normalize");
                yield pipeline.normalize();
            }
            case "audio" -> audio(pipeline, args);
            case "join" -> {
                reject(args, "join");
                yield pipeline.join();
            }
            case "publish" -> {
                reject(args, "publish");
                yield pipeline.publish();
            }
            default -> throw new Pipeline.Invalid("there is no step called /" + verb + " — " + allSteps());
        };
    }

    private static Pipeline download(List<String> args, String segment) {
        Matcher found = URL.matcher(segment);
        if (!found.find()) {
            throw refuse("download", "/download needs a link");
        }
        String url = trimTrailingPunctuation(found.group());
        // A link often arrives wrapped as <link>: chat clients do it, and so does a model told to
        // write <url>. The closing bracket is trailing punctuation already; this is the other one.
        args.removeIf(word -> url.equals(trimTrailingPunctuation(
                word.startsWith("<") ? word.substring(1) : word)));
        Integer height = heightIn(args, "download");
        reject(args, "download");
        return Pipeline.download(url, height);
    }

    private static Pipeline encode(Pipeline pipeline, List<String> args) {
        Integer height = heightIn(args, "encode");
        Container container = takeOne(args, Container::named);
        Quality quality = takeOne(args, Quality::named);
        reject(args, "encode");
        return pipeline.encode(container, height, quality);
    }

    private static Pipeline audio(Pipeline pipeline, List<String> args) {
        AudioFormat format = takeOne(args, AudioFormat::named);
        reject(args, "audio");
        return pipeline.audio(format);
    }

    /**
     * Takes the first argument the enum recognises and removes it, so whatever is left is by
     * definition a word no parameter of this step accepts — and gets quoted back rather than
     * silently ignored.
     */
    private static <T> T takeOne(List<String> args, java.util.function.Function<String, Optional<T>> as) {
        for (String arg : args) {
            Optional<T> found = as.apply(arg);
            if (found.isPresent()) {
                args.remove(arg);
                return found.get();
            }
        }
        return null;
    }

    private static List<Trim> windows(List<String> args) {
        if (args.isEmpty()) {
            throw refuse("cut", "/cut needs at least one range");
        }
        List<Trim> windows = new ArrayList<>();
        for (String arg : args) {
            try {
                windows.add(Trim.range(arg));
            } catch (IllegalArgumentException e) {
                throw refuse("cut", e.getMessage());
            }
        }
        return windows;
    }

    /**
     * Any height, not one off a ladder of eight. A number after {@code /encode} cannot be anything
     * else, so there is nothing here to disambiguate — the list exists in the sentence parser, where
     * a bare number really might be a playlist count.
     */
    private static Integer heightIn(List<String> args, String verb) {
        for (String arg : args) {
            String lower = arg.toLowerCase(Locale.ROOT);
            if (lower.equals("4k")) {
                args.remove(arg);
                return 2160;
            }
            Matcher m = HEIGHT.matcher(lower);
            if (!m.matches()) {
                continue;
            }
            int height = Integer.parseInt(m.group(1));
            if (height < MIN_HEIGHT || height > MAX_HEIGHT) {
                throw refuse(verb, "%dp is not a height this can produce — between %d and %d"
                        .formatted(height, MIN_HEIGHT, MAX_HEIGHT));
            }
            if (height % 2 != 0) {
                throw refuse(verb, "%dp is an odd number of lines, which no encoder will take — try %dp"
                        .formatted(height, height - 1));
            }
            args.remove(arg);
            return height;
        }
        return null;
    }

    private static void reject(List<String> args, String verb) {
        if (!args.isEmpty()) {
            throw refuse(verb, "/" + verb + " does not take \"" + args.getFirst() + "\"");
        }
    }

    private static String[] strip(String segment) {
        String cleaned = segment.strip();
        if (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1).strip();
        }
        return cleaned.isBlank() ? new String[0] : cleaned.split("\\s+");
    }

    private static String trimTrailingPunctuation(String url) {
        int end = url.length();
        while (end > 0 && ".,;:!?)]}>\"'«»".indexOf(url.charAt(end - 1)) >= 0) {
            end--;
        }
        return url.substring(0, end);
    }
}
