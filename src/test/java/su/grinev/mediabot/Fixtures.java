package su.grinev.mediabot;

import java.nio.file.Path;
import java.util.List;

/**
 * Configuration for a test, so a test about one value does not have to spell out the other thirty.
 *
 * <p>Written as a builder over the record rather than as a mock: these carry checks in their
 * constructors, and a mocked {@code AgentProperties} would let a test pass on a combination the
 * running bot refuses to start with.
 */
public final class Fixtures {

    private Fixtures() {
    }

    public static AgentProperties props() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private List<String> allowedHosts = List.of("youtube.com", "youtu.be", "instagram.com");
        private int maxHeight = 720;
        private int ownerMaxHeight = 2160;
        private long maxUploadBytes = 2L * 1024 * 1024 * 1024;
        private long sendFileUnderBytes = 50L * 1024 * 1024;
        private List<Long> owners = List.of();
        private List<Long> allowedChats = List.of();
        private Path workDir = Path.of("build", "test-work");
        private Path ffmpeg = Path.of("ffmpeg");
        private Path linksDir = Path.of("build", "test-public");
        private Path database = Path.of("build", "test-jobs.db");
        private int perChatLimit = 8;
        private int ttlHours = 1;
        private String token = "";
        private Path plugins = null;
        private String potProvider = null;

        /** The proof-of-origin provider, which is what makes an account unnecessary. */
        public Builder potProvider(String url, Path pluginDir) {
            this.potProvider = url;
            this.plugins = pluginDir;
            return this;
        }

        public Builder allowedHosts(String... hosts) {
            this.allowedHosts = List.of(hosts);
            return this;
        }

        public Builder heights(int guest, int owner) {
            this.maxHeight = guest;
            this.ownerMaxHeight = owner;
            return this;
        }

        public Builder uploads(long sendFileUnder, long maxUpload) {
            this.sendFileUnderBytes = sendFileUnder;
            this.maxUploadBytes = maxUpload;
            return this;
        }

        public Builder owners(Long... ids) {
            this.owners = List.of(ids);
            return this;
        }

        public Builder allowedChats(Long... ids) {
            this.allowedChats = List.of(ids);
            return this;
        }

        public Builder workDir(Path directory) {
            this.workDir = directory;
            return this;
        }

        public Builder ffmpeg(Path binary) {
            this.ffmpeg = binary;
            return this;
        }

        public Builder linksDir(Path directory) {
            this.linksDir = directory;
            return this;
        }

        public Builder database(Path path) {
            this.database = path;
            return this;
        }

        public Builder perChatLimit(int limit) {
            this.perChatLimit = limit;
            return this;
        }

        public Builder ttlHours(int hours) {
            this.ttlHours = hours;
            return this;
        }

        public Builder token(String token) {
            this.token = token;
            return this;
        }

        public AgentProperties build() {
            return new AgentProperties(
                    new AgentProperties.Llm("http://localhost:11434/v1", "", "", "test-model",
                            256, 0.0, 60, false),
                    new AgentProperties.Telegram(token, "TestBot", "https://api.telegram.org",
                            maxUploadBytes, sendFileUnderBytes, owners, allowedChats),
                    new AgentProperties.Media(Path.of("yt-dlp"), ffmpeg, workDir,
                            allowedHosts, 60, 600, null, null, null, plugins, potProvider, null,
                            maxHeight, ownerMaxHeight),
                    new AgentProperties.Links("http://test.local:1488", linksDir, ttlHours),
                    new AgentProperties.Jobs(database, 1, perChatLimit, 48));
        }
    }
}
