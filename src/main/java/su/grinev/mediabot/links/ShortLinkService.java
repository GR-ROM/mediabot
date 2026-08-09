package su.grinev.mediabot.links;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import su.grinev.mediabot.AgentProperties;
import su.grinev.mediabot.jobs.Job;
import su.grinev.mediabot.jobs.JobQueue;
import su.grinev.mediabot.media.MediaStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Slf4j
@Component
public class ShortLinkService {

    private static final int CODE_BYTES = 16;

    private final ShortLinkRepository repository;
    private final MediaStore media;
    private final JobQueue jobs;
    private final SecureRandom random = new SecureRandom();
    private final String baseUrl;
    private final Path root;
    private final Duration ttl;

    // Written out rather than @RequiredArgsConstructor: three of these fields are values read off
    // the configuration, and a generated constructor asks Spring to inject a String and a Duration.
    public ShortLinkService(ShortLinkRepository repository, MediaStore media, JobQueue jobs,
                            AgentProperties props) {
        this.repository = repository;
        this.media = media;
        this.jobs = jobs;
        this.baseUrl = props.links().baseUrl();
        this.root = props.links().dir();
        this.ttl = Duration.ofHours(props.links().ttlHours());
    }

    @PostConstruct
    void prepare() throws IOException {
        Files.createDirectories(root);
        deleteExpired();
    }

    public ShortLink share(Job job) throws IOException {
        // Not orElse: that evaluates publish() every time and would move the file, and mint a
        // second code, even when the link asked for is sitting right there.
        Optional<ShortLink> live = findActiveByJobId(job.id());
        return live.isPresent() ? live.get() : publish(job);
    }

    public Optional<ShortLink> findActiveByJobId(long jobId) {
        return repository.findByJobId(jobId).flatMap(link -> findActiveByCode(link.code()));
    }

    /**
     * Every file the job produced, each behind its own link.
     *
     * <p>Two cuts are two files and two links, which is what the {@code links} table always allowed
     * — a row per code, keyed by job — and what the single {@code result_path} beside it could not
     * say. The job stays one job: it is cancelled as one, it expires as one, and it is announced in
     * the chat once.
     */
    public List<ShortLink> publishAll(Job job) throws IOException {
        List<Path> files = job.results();
        if (files.isEmpty()) {
            throw new IOException("job " + job.id() + " has no file to publish");
        }
        List<ShortLink> published = new ArrayList<>();
        for (Path file : files) {
            published.add(publishOne(job, file, files.size() == 1));
        }
        // Held until every piece has moved: releasing the working directory takes the rest of the
        // job's files with it, and the second piece is still in there while the first is published.
        media.release(job.id());
        return List.copyOf(published);
    }

    public List<ShortLink> shareAll(Job job) throws IOException {
        List<ShortLink> live = findAllActiveByJobId(job.id());
        return live.isEmpty() ? publishAll(job) : live;
    }

    public List<ShortLink> findAllActiveByJobId(long jobId) {
        return repository.findAllByJobId(jobId).stream()
                .map(link -> findActiveByCode(link.code()))
                .flatMap(Optional::stream)
                .toList();
    }

    public ShortLink publish(Job job) throws IOException {
        return publishAll(job).getFirst();
    }

    private ShortLink publishOne(Job job, Path source, boolean onlyOne) throws IOException {
        if (source == null || !Files.isRegularFile(source)) {
            throw new IOException("job " + job.id() + " has no file to publish");
        }

        String code = newCode();
        Path directory = root.resolve(code);
        Files.createDirectories(directory);
        Path target = directory.resolve(source.getFileName().toString());
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);

        Instant now = Instant.now();
        ShortLink link = new ShortLink(code, job.id(), job.chatId(), target, target.getFileName().toString(), Files.size(target), now, now.plus(ttl));
        repository.save(link);
        if (onlyOne) {
            rememberWhereItWent(link);
        }
        log.info("job {} published as {} until {}", job.id(), urlOf(link), link.expiresAt());
        return link;
    }

    private void rememberWhereItWent(ShortLink link) {
        try {
            jobs.updateResult(link.jobId(), link.file(), link.sizeBytes());
        } catch (RuntimeException e) {
            log.debug("could not update job {} with its new path: {}", link.jobId(), e.toString());
        }
    }

    public Optional<ShortLink> findActiveByCode(String code) {
        return repository.findByCode(code)
                .filter(link -> {
                    if (link.expiredAt(Instant.now())) {
                        // Marked and refused, never deleted here: a request from somebody's phone
                        // is not the place to walk a directory tree. The sweep takes the bytes.
                        repository.expireOverdue(Instant.now());
                        markExpired(link.jobId());
                        return false;
                    }
                    return true;
                })
                .filter(link -> {
                    if (!Files.isRegularFile(link.file())) {
                        log.warn("link {} points at {}, which is gone", code, link.file());
                        repository.deleteByCode(code);
                        markExpired(link.jobId());
                        return false;
                    }
                    return true;
                });
    }

    public String urlOf(ShortLink link) {
        return baseUrl + "/d/" + link.code();
    }

    public Duration linkLifetime() {
        return ttl;
    }

    /** How many files one sweep will take on, so a long backlog cannot hold the thread all day. */
    private static final int SWEEP_BATCH = 200;

    /**
     * The first of the two passes: rows only.
     *
     * <p>Nothing is deleted here. A link stops working the moment this runs — the row says so, and
     * that is what {@link #findActiveByCode} answers from — while the bytes go later and separately.
     * Splitting them is what makes expiry exact: marking is one statement that either happened or
     * did not, where deleting a directory tree can half-succeed, and a half-succeeded delete must
     * not be the thing that decides whether a link is live.
     */
    @Scheduled(fixedDelay = 5, initialDelay = 1, timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    public void expireOverdue() {
        Instant now = Instant.now();
        List<ShortLink> overdue = repository.findExpiredBefore(now);
        int marked = repository.expireOverdue(now);
        // The job says EXPIRED so /status and /link can explain the link that no longer works,
        // rather than reporting a job that is DONE and pointing at nothing.
        overdue.stream().map(ShortLink::jobId).distinct().forEach(this::markExpired);
        if (marked > 0) {
            log.info("{} link(s) expired", marked);
        }
    }

    /**
     * The second pass: bytes only, in batches, from whatever the first pass marked.
     *
     * <p>Deleting the rows after the files rather than before is deliberate. A crash between the two
     * leaves a row pointing at a directory that is gone, which the next sweep tidies without anybody
     * noticing; the other order leaves files nothing remembers, and those are found by running out
     * of disk.
     */
    @Scheduled(fixedDelay = 5, initialDelay = 2, timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    public void sweepExpired() {
        List<ShortLink> batch = repository.findExpired(SWEEP_BATCH);
        if (batch.isEmpty()) {
            return;
        }
        long freed = 0;
        List<String> swept = new ArrayList<>();
        for (ShortLink link : batch) {
            freed += link.sizeBytes();
            deleteTree(link.file().getParent());
            swept.add(link.code());
        }
        int rows = repository.deleteByCodes(swept);
        log.info("swept {} expired link(s), {} freed", rows, su.grinev.mediabot.text.Sizes.bytes(freed));
    }

    /** Both passes back to back, for a start-up tidy and for a test that wants the whole story. */
    public void deleteExpired() {
        expireOverdue();
        sweepExpired();
    }


    private void markExpired(long jobId) {
        try {
            jobs.expire(jobId);
        } catch (RuntimeException e) {
            log.debug("could not mark job {} expired: {}", jobId, e.toString());
        }
    }

    private String newCode() {
        byte[] bytes = new byte[CODE_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void deleteTree(Path path) {
        if (path == null || !path.startsWith(root) || path.equals(root) || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> tree = Files.walk(path)) {
            tree.sorted(Comparator.reverseOrder()).forEach(entry -> {
                try {
                    Files.deleteIfExists(entry);
                } catch (IOException e) {
                    log.debug("could not delete {}: {}", entry, e.toString());
                }
            });
        } catch (IOException e) {
            log.warn("could not clean up {}: {}", path, e.toString());
        }
    }
}
