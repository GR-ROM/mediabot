package su.grinev.mediabot.media;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers what a host said about a URL, for a few minutes.
 *
 * <p>What makes it worth having is not speed but the shape it allows elsewhere: because a probe is
 * nearly free the second time, {@code enqueue_job} can check a requested height against what
 * actually exists on every call, instead of trusting the model to have probed first and to have read
 * the answer correctly. A guarantee that costs a network round trip is one that gets skipped.
 *
 * <p>Short-lived entries. Formats and their sizes are a property of the host at a moment, and a URL
 * probed an hour ago may be a video that has since been made private.
 */
@Component
@Slf4j
public class ProbeCache {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final int MAX_ENTRIES = 200;

    private record Entry(MediaInfo info, Instant at) {}

    private final YtDlp ytDlp;

    private final Map<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    public ProbeCache(YtDlp ytDlp) {
        this.ytDlp = ytDlp;
    }

    /**
     * @param url already checked by {@link UrlGuard}
     * @throws IOException if the host had to be asked and would not answer
     */
    public MediaInfo probe(String url) throws IOException, InterruptedException {
        synchronized (entries) {
            Entry cached = entries.get(url);
            if (cached != null && Duration.between(cached.at(), Instant.now()).compareTo(TTL) < 0) {
                return cached.info();
            }
        }
        // Deliberately outside the lock: probing is seconds of network, and holding the map for it
        // would serialise every chat behind whichever one probed first.
        MediaInfo info = ytDlp.probe(url);
        synchronized (entries) {
            entries.put(url, new Entry(info, Instant.now()));
        }
        return info;
    }

    /** The cached answer if there is one, without asking the host. */
    public java.util.Optional<MediaInfo> peek(String url) {
        synchronized (entries) {
            Entry cached = entries.get(url);
            if (cached == null || Duration.between(cached.at(), Instant.now()).compareTo(TTL) >= 0) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(cached.info());
        }
    }
}
