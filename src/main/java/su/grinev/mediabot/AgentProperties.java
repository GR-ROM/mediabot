package su.grinev.mediabot;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

/**
 * Every knob, so the bot can be pointed at a different model host or Bot API server without code
 * changes.
 *
 * <p>Each section checks itself in its constructor, so a value that cannot work stops the process at
 * startup and names itself. Left unchecked, the failures arrive far from their cause and do not look
 * like configuration: {@code max-steps: 0} is a loop that calls no tool and reports that the request
 * could not be finished, and a section missing from the YAML is a null that surfaces as an NPE inside
 * whichever bean touched it first.
 */
@ConfigurationProperties(prefix = "mediabot")
public record AgentProperties(
        Llm llm,
        Telegram telegram,
        Media media,
        Links links,
        Jobs jobs
) {

    public AgentProperties {
        // Named individually: "mediabot.jobs is missing" is actionable, an NPE later is not.
        require(llm != null, "mediabot.llm is missing from the configuration");
        require(telegram != null, "mediabot.telegram is missing from the configuration");
        require(media != null, "mediabot.media is missing from the configuration");
        require(links != null, "mediabot.links is missing from the configuration");
        require(jobs != null, "mediabot.jobs is missing from the configuration");
    }

    /**
     * @throws IllegalArgumentException naming the key and what it needs, which is the whole point of
     *     checking here rather than where the value is finally used
     */
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * The model driving the tool-calling loop.
     *
     * @param baseUrl OpenAI-compatible base, e.g. http://192.168.1.104:11434/v1
     * @param apiKey sent as a Bearer token when no authHeader is given
     * @param authHeader full Authorization value, sent verbatim; wins over apiKey
     * @param model must support tool calling
     * @param maxTokens answer budget
     * @param temperature sampling temperature
     * @param timeoutSeconds per-request timeout
     * @param preflight ask the server what it has before the first request does; turn it off to
     *     start without a reachable model server
     */
    public record Llm(String baseUrl, String apiKey, String authHeader, String model,
                      int maxTokens, double temperature, int timeoutSeconds, boolean preflight) {

        public Llm {
            require(baseUrl != null && !baseUrl.isBlank(),
                    "mediabot.llm.base-url must be set, e.g. http://192.168.1.104:11434/v1");
            require(model != null && !model.isBlank(),
                    "mediabot.llm.model must name a model that supports tool calling");
            require(maxTokens >= 1, "mediabot.llm.max-tokens must be at least 1, got " + maxTokens);
            require(temperature >= 0.0 && temperature <= 2.0,
                    "mediabot.llm.temperature must be between 0 and 2, got " + temperature);
            require(timeoutSeconds >= 1,
                    "mediabot.llm.timeout-seconds must be at least 1, got " + timeoutSeconds);
        }
    }

    /**
     * The chat side.
     *
     * @param token from BotFather
     * @param username the bot's @name, which the library wants for long polling
     * @param baseUrl the Bot API to talk to. The public one caps an upload at 50 MB; a local
     *     telegram-bot-api raises that to 2 GB, which is the only reason most of these downloads
     *     can be delivered at all
     * @param maxUploadBytes what this server will accept, so a file too big is caught before it is
     *     downloaded rather than after
     * @param allowedChats who may use the bot; empty means anybody, which on a personal bot means
     *     anybody who finds it can spend your bandwidth
     */
    public record Telegram(String token, String username, String baseUrl, long maxUploadBytes,
                           long sendFileUnderBytes, List<Long> owners, List<Long> allowedChats) {

        /** Whether a file this big should go into the chat itself rather than as a link. */
        public boolean shouldSendFile(long sizeBytes) {
            return sizeBytes > 0 && sizeBytes <= sendFileUnderBytes;
        }

        public Telegram {
            // The token is deliberately not required here. Everything except the chat transport
            // works without one, and a config check or a test that cannot build the context at all
            // for want of a BotFather token is a worse failure than a bot that starts and says
            // loudly that it has no token.
            require(maxUploadBytes >= 1,
                    "mediabot.telegram.max-upload-bytes must be at least 1, got " + maxUploadBytes);
            // Owners live here rather than in the table on purpose: a database that will not open,
            // or a list somebody emptied, must not lock the one person who could put it right out
            // of their own bot.
            owners = owners == null ? List.of() : List.copyOf(owners);
            allowedChats = allowedChats == null ? List.of() : List.copyOf(allowedChats);
        }

        public boolean hasToken() {
            return token != null && !token.isBlank();
        }
    }

    /**
     * The external binaries and where their output lands.
     *
     * @param ytDlp path to the yt-dlp executable
     * @param ffmpeg path to ffmpeg; yt-dlp needs it to mux separate video and audio streams
     * @param workDir root for per-job temporary directories
     * @param allowedHosts the only hosts a URL may point at. Not a convenience: the URL comes from
     *     a chat message and the arguments around it come from a model, so this is the boundary
     * @param probeTimeoutSeconds how long metadata may take
     * @param downloadTimeoutSeconds how long one download may take before it is killed
     * @param cookiesFile a Netscape-format cookies.txt. YouTube refuses most requests without one
     *     — "Sign in to confirm you're not a bot" — and there is no clever way around that; the
     *     supported answer is to give yt-dlp cookies from a signed-in session. Null means none
     * @param cookiesFromBrowser read cookies straight from a browser profile instead, e.g.
     *     {@code chrome} or {@code firefox}. Convenient when it works, but a running Chromium keeps
     *     its cookie database locked, so the file is the reliable option on a server
     * @param jsRuntime a JavaScript runtime for yt-dlp, as {@code name} or {@code name:path} —
     *     {@code node:C:\...\node.exe}. The second half of what YouTube needs: it hands out an "n
     *     challenge" that has to be executed, and without a runtime the formats come back empty
     *     with only a warning to say why. Names are yt-dlp's: node, deno, bun, quickjs
     */
    public record Media(Path ytDlp, Path ffmpeg, Path workDir, List<String> allowedHosts,
                        int probeTimeoutSeconds, int downloadTimeoutSeconds,
                        Path cookiesFile, String cookiesFromBrowser, String jsRuntime,
                        Path ytDlpPlugins, String potProvider, String canaryUrl,
                        int maxHeight, int ownerMaxHeight) {

        public Media {
            require(ytDlp != null, "mediabot.media.yt-dlp must point at the yt-dlp executable");
            require(workDir != null, "mediabot.media.work-dir must be set");
            require(allowedHosts != null && !allowedHosts.isEmpty(),
                    "mediabot.media.allowed-hosts must list at least one host — an empty allowlist "
                            + "would let the bot fetch anything anyone sends it");
            allowedHosts = List.copyOf(allowedHosts);
            require(probeTimeoutSeconds >= 1,
                    "mediabot.media.probe-timeout-seconds must be at least 1, got "
                            + probeTimeoutSeconds);
            require(downloadTimeoutSeconds >= 1,
                    "mediabot.media.download-timeout-seconds must be at least 1, got "
                            + downloadTimeoutSeconds);
            // Both at once is not an error, but only one of them can win, and silently ignoring the
            // other is how you spend an afternoon wondering why the cookies file has no effect.
            require(cookiesFile == null || cookiesFromBrowser == null
                            || cookiesFromBrowser.isBlank(),
                    "set either mediabot.media.cookies-file or cookies-from-browser, not both");
            // The oldest video on YouTube: nineteen seconds, and it has been there since 2005. What
            // the canary needs is something that will not be taken down and is cheap to ask about.
            canaryUrl = canaryUrl == null || canaryUrl.isBlank()
                    ? "https://www.youtube.com/watch?v=jNQXAC9IVRw"
                    : canaryUrl.strip();
        }

        public boolean hasJsRuntime() {
            return jsRuntime != null && !jsRuntime.isBlank();
        }

        /**
         * Whether a proof-of-origin provider is configured.
         *
         * <p>This is the thing that answers YouTube's "Sign in to confirm you're not a bot" without
         * an account: the token it wants is produced by running its own JavaScript, and a service
         * beside the bot does that. Cookies are the fallback when there is no provider, and they
         * rot; a token is minted per request and never has to be refreshed.
         */
        public boolean hasPotProvider() {
            return potProvider != null && !potProvider.isBlank();
        }

        public boolean hasPlugins() {
            return ytDlpPlugins != null;
        }

        /** Whether anything at all is configured to authenticate with. */
        public boolean hasCookies() {
            return cookiesFile != null
                    || (cookiesFromBrowser != null && !cookiesFromBrowser.isBlank());
        }
    }

    /**
     * How a finished file reaches the person who asked for it.
     *
     * @param baseUrl what the link is built from, so it has to be an address the user's phone can
     *     reach. The default is localhost, which works for trying it out and for nothing else
     * @param dir where finished files are moved to, out of the per-job working directory
     * @param ttlHours how long a link keeps working; after this both the row and the file go
     */
    public record Links(String baseUrl, Path dir, int ttlHours) {

        public Links {
            require(baseUrl != null && !baseUrl.isBlank(),
                    "mediabot.links.base-url must be set, e.g. https://media.example.com");
            require(dir != null, "mediabot.links.dir must be set");
            require(ttlHours >= 1,
                    "mediabot.links.ttl-hours must be at least 1, got " + ttlHours);
            baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        }
    }

    /**
     * The queue that does the downloading.
     *
     * @param databasePath where jobs live. Not disposable while anything is queued: this is what
     *     lets a restart say "your download was interrupted" instead of leaving somebody waiting
     *     for a file that nothing is producing any more
     * @param concurrency downloads running at once
     * @param perChatLimit queued jobs one chat may hold, so one person cannot fill the queue
     * @param retentionHours how long finished jobs stay in the table before being swept
     */
    public record Jobs(Path databasePath, int concurrency, int perChatLimit, int retentionHours) {

        public Jobs {
            require(databasePath != null, "mediabot.jobs.database-path must be set");
            require(concurrency >= 1,
                    "mediabot.jobs.concurrency must be at least 1, got " + concurrency);
            require(perChatLimit >= 1,
                    "mediabot.jobs.per-chat-limit must be at least 1, got " + perChatLimit);
            require(retentionHours >= 1,
                    "mediabot.jobs.retention-hours must be at least 1, got " + retentionHours);
        }
    }

}
