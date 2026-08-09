package su.grinev.mediabot.jobs;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import su.grinev.mediabot.media.MediaInfo;
import su.grinev.mediabot.media.ProbeCache;
import su.grinev.mediabot.media.UrlGuard;

import java.util.Optional;

/**
 * Turning "download this" into a queued job, with every rule that must hold however the request
 * arrived.
 *
 * <p>Its own class because there are two callers and they must not diverge. The tools are one; the
 * deterministic fallback is the other, and it runs precisely when the model is unavailable — which
 * is the worst possible moment for the checks to live in the tool layer instead. An upscale refused
 * only when the model happens to be up is not a guarantee.
 *
 * <p>One entry point taking a whole {@link JobSpec}, because the checks are the same three whatever
 * the scenario: the URL has to be one this bot may fetch, a live stream has no end and cannot be
 * downloaded whole, and no source can be asked for a height it does not have.
 */
@Component
@RequiredArgsConstructor
public class DownloadRequests {

    private final UrlGuard guard;
    private final ProbeCache probes;
    private final JobQueue queue;

    /** What came of asking. */
    public sealed interface Outcome {
        record Queued(Job job, Optional<MediaInfo> info, Optional<Long> estimatedBytes) implements Outcome {}
        record Refused(String reason) implements Outcome {}
    }

    public Outcome submit(JobSpec requested) {
        return submit(requested, requested.maxHeight());
    }

    /**
     * @param askedFor the height the person actually typed, or null when they named none. Not the
     *     same as the one on the spec: a ceiling put there by policy is a bound on what to fetch,
     *     never a demand, and refusing a plain download because the ceiling is taller than the
     *     video would be refusing to answer the commonest request there is.
     */
    public Outcome submit(JobSpec requested, Integer askedFor) {
        JobSpec spec;
        try {
            spec = requested.withUrl(guard.check(requested.url()));
        } catch (UrlGuard.Rejected e) {
            return new Outcome.Refused(e.getMessage());
        }

        Optional<MediaInfo> info = probeQuietly(spec.url());
        if (info.isPresent() && info.get().isLive()) {
            return new Outcome.Refused(
                    "this is a live stream — it has no end, so it cannot be downloaded whole");
        }
        String upscale = upscaleRefusal(askedFor, info.orElse(null));
        if (upscale != null) {
            return new Outcome.Refused(upscale);
        }

        try {
            Job job = queue.enqueue(spec.withTitle(info.map(MediaInfo::title).orElse(null)));
            return new Outcome.Queued(job, info,
                    spec.scenario() == JobKind.AUDIO
                            ? Optional.empty()
                            : info.flatMap(i -> i.estimatedSize(spec.maxHeight())));
        } catch (JobQueue.Refused e) {
            return new Outcome.Refused(e.getMessage());
        }
    }

    /**
     * The rule the whole design hangs on: you cannot have a height the source does not have.
     *
     * @return why not, or null when the request is possible
     */
    public String upscaleRefusal(Integer requested, MediaInfo info) {
        if (requested == null || info == null) {
            return null;
        }
        int available = info.maxHeight();
        if (available <= 0 || requested <= available) {
            return null;
        }
        String heights = info.heights().stream()
                .map(h -> h + "p")
                .reduce((a, b) -> a + ", " + b)
                .orElse("nothing");
        return ("this video tops out at %dp, so %dp cannot be had from it — upscaling would only "
                + "inflate the file without adding a single detail. Available: %s")
                .formatted(available, requested, heights);
    }

    /**
     * A probe that fails does not fail the request.
     *
     * <p>Without it the height check cannot run, and proceeding is the right trade: yt-dlp falls
     * back to the best available format, so the worst case is a lower quality than asked for —
     * against refusing to download at all because a metadata call timed out.
     */
    private Optional<MediaInfo> probeQuietly(String url) {
        try {
            return Optional.of(probes.probe(url));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
