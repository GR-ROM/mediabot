package su.grinev.mediabot.route;

import su.grinev.mediabot.graph.Pipeline;
import su.grinev.mediabot.jobs.JobSpec;
import su.grinev.mediabot.media.Container;
import su.grinev.mediabot.media.Quality;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Every command the bot has, and everything the help text says about them.
 *
 * <p>The help used to be a literal listing these by hand, and it drifted twice — a command that had
 * been renamed and one that never existed. It is generated from here now, so the only way for the
 * two to disagree is for a command to be missing from this enum, in which case it does not exist.
 */
public enum Command {

    PLAYLIST(true, false,
            List.of("playlist"),
            List.of("playlist", "channel", "every video", "all of them", "all the videos", "batch"),
            "5 720p <link>", "the first N of a playlist") {
        @Override
        Request build(Args args) {
            return new Request.Playlist(args.url(), args.height(), args.count(5));
        }
    },

    AUDIO(true, false,
            List.of("audio", "sound", "mp3"),
            List.of("audio", "sound", "music", "mp3", "m4a", "opus", "song", "track", "listen"),
            "mp3 <link>", "just the sound") {
        @Override
        Request build(Args args) {
            return new Request.Fetch(JobSpec.audio(args.chatId(), args.url(), args.audioFormat()));
        }
    },

    TRANSCODE(true, false,
            List.of("transcode", "compress", "convert"),
            List.of("transcode", "re-encode", "reencode", "recode", "convert", "compress",
                    "shrink", "smaller", "squeeze", "too big"),
            "480p <link>", "re-encode it smaller") {
        @Override
        Request build(Args args) {
            return new Request.Fetch(JobSpec.transcode(args.chatId(), args.url(), args.height()));
        }
    },

    /**
     * A download an Apple device will actually open.
     *
     * <p>Before this, the only answer to "it will not play on my phone" was to know that
     * {@code /download} does not re-encode at all: whatever the host served is what arrives, merged
     * into an mp4 container. When that is HEVC, or ten-bit, or an audio codec iOS declines, the
     * container says mp4 and nothing plays it — which reads as a broken file rather than an
     * unsupported one.
     *
     * <p>So this one re-encodes rather than hoping. It is the only command that does so
     * unconditionally, and that is the whole of its purpose: the source stops mattering.
     */
    IDOWNLOAD(true, false,
            List.of("idownload", "iphone"),
            List.of("iphone", "ipad", "for apple", "apple device"),
            "720p <link>", "re-encode so an Apple device will play it") {
        @Override
        Request build(Args args) {
            return new Request.Fetch(JobSpec.of(args.chatId(), Pipeline.download(args.url(), null)
                    .encode(Container.MP4, args.height(), Quality.DEFAULT)
                    .build()));
        }
    },

    // Slash only, and deliberately without phrases: "smaller" already belongs to TRANSCODE, and a
    // shorthand that quietly outranked a command somebody spelled out would be a surprise.
    SMALL(true, false, List.of("small", "tiny"), List.of(),
            "<link>", "shorthand for /download <link> /encode LOW") {
        @Override
        Request build(Args args) {
            return new Request.Fetch(JobSpec.of(args.chatId(), Pipeline.download(args.url(), null)
                    .encode(Container.DEFAULT, args.height(), Quality.LOW)
                    .build()));
        }
    },

    DOWNLOAD(true, false,
            List.of("download", "get", "fetch"),
            List.of("download", "fetch", "grab", "save", "get", "pull"),
            "720p <link>", "download it, at most that tall") {
        @Override
        Request build(Args args) {
            return new Request.Fetch(JobSpec.download(args.chatId(), args.url(), args.height()));
        }
    },

    CANCEL(false, false,
            List.of("cancel", "stop"),
            List.of("cancel", "stop", "abort", "drop", "kill", "never mind", "forget"),
            "<job>", "stop one") {
        @Override
        Request build(Args args) {
            return new Request.Cancel(args.jobNumber().orElseThrow(this::missing));
        }
    },

    STATUS(false, false,
            List.of("status", "queue"),
            List.of("status", "queue", "queued", "running", "progress", "downloading",
                    "still going", "done yet"),
            "", "what is running") {
        @Override
        Request build(Args args) {
            return new Request.Status();
        }
    },

    LINK(false, false,
            List.of("link"),
            List.of("link", "where is", "where's", "lost the", "send it again", "once more"),
            "[job]", "the link again") {
        @Override
        Request build(Args args) {
            return new Request.Link(args.jobNumber().orElse(null));
        }
    },

    HELP(false, false,
            List.of("help", "start", "commands"),
            List.of("help", "what can you do", "how do i", "how does this work", "options",
                    "instructions"),
            "", "this") {
        @Override
        Request build(Args args) {
            return new Request.Help();
        }
    },

    // Administration. No phrases at all: these are answered only when written as a command, so a
    // sentence can never be read as one, and whether the person may run them is settled elsewhere.
    ALLOW(false, true, List.of("allow", "grant"), List.of(),
            "<chat id> [note]", "let somebody in") {
        @Override
        Request build(Args args) {
            long who = args.chatIdArgument().orElseThrow(this::missing);
            return new Request.Allow(who, args.noteAfter(who));
        }
    },

    DENY(false, true, List.of("deny", "revoke", "ban"), List.of(),
            "<chat id>", "take them back out") {
        @Override
        Request build(Args args) {
            return new Request.Deny(args.chatIdArgument().orElseThrow(this::missing));
        }
    },

    ALLOWED(false, true, List.of("allowed", "access", "whitelist"), List.of(),
            "", "who is on the list") {
        @Override
        Request build(Args args) {
            return new Request.Allowed();
        }
    };

    /**
     * Refused because a command was recognised and could not be served as written.
     *
     * <p>Distinct from "not understood": somebody who typed {@code /transcode} has said what they
     * want and deserves to be told what it takes, not handed the whole manual.
     */
    public static class Incomplete extends IllegalArgumentException {
        public Incomplete(String message) {
            super(message);
        }
    }

    /** What this command turns its arguments into. */
    abstract Request build(Args args);

    /**
     * Builds it, or says what this command needs — which is the difference between a message the
     * bot could not read and one it read perfectly well and could not act on.
     */
    public Request read(Args args) {
        if (needsUrl && !args.hasUrl()) {
            throw missing();
        }
        return build(args);
    }

    Incomplete missing() {
        return new Incomplete("%s needs %s".formatted(slashName(), args.isBlank() ? "no arguments"
                : args));
    }

    private final boolean needsUrl;
    private final boolean ownerOnly;
    private final List<String> slashNames;
    private final List<String> phrases;
    private final String args;
    private final String description;

    Command(boolean needsUrl, boolean ownerOnly, List<String> slashNames, List<String> phrases,
            String args, String description) {
        this.needsUrl = needsUrl;
        this.ownerOnly = ownerOnly;
        this.slashNames = slashNames;
        this.phrases = phrases;
        this.args = args;
        this.description = description;
    }

    public boolean needsUrl() {
        return needsUrl;
    }

    public boolean ownerOnly() {
        return ownerOnly;
    }

    public String slashName() {
        return "/" + slashNames.getFirst();
    }

    /** One help line, padded so a column of them lines up. */
    public String helpLine() {
        String written = args.isBlank() ? slashName() : slashName() + " " + args;
        return "  %-26s %s".formatted(written, description);
    }

    public static String everyday() {
        return listing(command -> !command.ownerOnly());
    }

    public static String ownerOnlyListing() {
        return listing(Command::ownerOnly);
    }

    private static String listing(java.util.function.Predicate<Command> which) {
        return Arrays.stream(values())
                .filter(which)
                .map(Command::helpLine)
                .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
    }

    public static Optional<Command> bySlashName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(command -> command.slashNames.contains(lower))
                .findFirst();
    }

    public static Optional<Command> spokenWithUrl(String words) {
        return spoken(words, true);
    }

    public static Optional<Command> spokenWithoutUrl(String words) {
        return spoken(words, false);
    }

    private static Optional<Command> spoken(String words, boolean urlPresent) {
        return Arrays.stream(values())
                .filter(command -> !command.ownerOnly())
                .filter(command -> command.needsUrl == urlPresent)
                .filter(command -> command.mentionedIn(words))
                .findFirst();
    }

    private boolean mentionedIn(String words) {
        return phrases.stream().anyMatch(words::contains);
    }
}
