package su.grinev.mediabot.jobs;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.Accessors;

import su.grinev.mediabot.graph.Graph;

import java.nio.file.Path;
import java.time.Instant;

@Getter
@Accessors(fluent = true)
public class Job {

    private final long id;
    private final long chatId;
    private final JobKind kind;
    private final String url;
    private final Integer maxHeight;
    private final String audioFormat;
    private final Graph graph;
    private final Instant createdAt;

    private volatile JobState state;
    private volatile Instant updatedAt;
    private volatile String title;
    private volatile Path result;

    @Getter(AccessLevel.NONE)
    private volatile java.util.List<Path> results;

    private volatile long sizeBytes;
    private volatile String error;

    @Getter(AccessLevel.NONE)
    private volatile Thread worker;

    public Job(long id, long chatId, JobKind kind, String url, Integer maxHeight,
               String audioFormat, JobState state, Instant createdAt, Instant updatedAt,
               String title, Path result, long sizeBytes, String error) {
        this(id, chatId, kind, url, maxHeight, audioFormat, null, state, createdAt, updatedAt,
                title, result, sizeBytes, error);
    }

    public Job(long id, long chatId, JobKind kind, String url, Integer maxHeight,
               String audioFormat, Graph graph, JobState state, Instant createdAt,
               Instant updatedAt, String title, Path result, long sizeBytes, String error) {
        this.id = id;
        this.chatId = chatId;
        this.kind = kind;
        this.url = url;
        this.maxHeight = maxHeight;
        this.audioFormat = audioFormat;
        this.graph = graph == null
                ? new JobSpec(chatId, kind, url, maxHeight, audioFormat, title).graph()
                : graph;
        this.state = state;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.title = title;
        this.result = result;
        this.sizeBytes = sizeBytes;
        this.error = error;
    }

    /**
     * Everything the job produced. One file for all the shapes that existed before a job could
     * branch, so a caller that wants "the" result can keep asking for {@link #result()}.
     */
    public java.util.List<Path> results() {
        java.util.List<Path> all = results;
        if (all != null && !all.isEmpty()) {
            return all;
        }
        return result == null ? java.util.List.of() : java.util.List.of(result);
    }

    void results(java.util.List<Path> produced) {
        this.results = produced == null ? null : java.util.List.copyOf(produced);
    }

    public String describe() {
        if (title != null && !title.isBlank()) {
            return title;
        }
        return url;
    }

    void claimedBy(Thread thread) {
        this.worker = thread;
    }

    void released() {
        this.worker = null;
    }

    public boolean interrupt() {
        Thread thread = worker;
        worker = null;
        if (thread == null) {
            return false;
        }
        thread.interrupt();
        return true;
    }

    void sync(Job row) {
        this.state = row.state;
        this.updatedAt = row.updatedAt;
        this.title = row.title;
        this.result = row.result;
        this.results = row.results;
        this.sizeBytes = row.sizeBytes;
        this.error = row.error;
    }
}
