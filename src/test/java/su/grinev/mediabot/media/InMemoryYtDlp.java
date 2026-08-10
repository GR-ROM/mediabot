package su.grinev.mediabot.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import su.grinev.mediabot.Fixtures;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A host that answers without one existing.
 *
 * <p>The one true external in this project: everything else a test needs can be run for real, and
 * this cannot — it is a process, a network and somebody else's website. So it is written out rather
 * than stubbed per test, which keeps the answers consistent: a video that reports 1080p also reports
 * the audio stream that goes with it, and a probe that fails fails the same way every time.
 */
public class InMemoryYtDlp extends YtDlp {

    private MediaInfo answer = video(1080, 720, 360);
    private IOException refusal;
    private List<PlaylistItem> entries = List.of();

    public InMemoryYtDlp() {
        super(new ObjectMapper(), Fixtures.props());
    }

    /** What a host publishes for one video: a stream per height, and one audio track beside them. */
    public static MediaInfo video(int... heights) {
        List<MediaInfo.Format> formats = new ArrayList<>();
        for (int height : heights) {
            formats.add(new MediaInfo.Format("v" + height, "mp4", height, 30,
                    height * 100_000L, "avc1", "none"));
        }
        formats.add(new MediaInfo.Format("audio", "m4a", 0, 0, 2_000_000L, "none", "mp4a"));
        return new MediaInfo("Me at the zoo", "jawed", 19, false, formats);
    }

    public InMemoryYtDlp publishing(MediaInfo info) {
        this.answer = info;
        this.refusal = null;
        return this;
    }

    public InMemoryYtDlp streaming() {
        this.answer = new MediaInfo("live now", "somebody", 0, true, List.of());
        return this;
    }

    public InMemoryYtDlp refusing(String why) {
        this.refusal = new IOException(why);
        return this;
    }

    public InMemoryYtDlp withPlaylist(String... titles) {
        List<PlaylistItem> items = new ArrayList<>();
        for (int i = 0; i < titles.length; i++) {
            items.add(new PlaylistItem(i + 1, titles[i], "https://www.youtube.com/watch?v=e" + i, 60));
        }
        this.entries = items;
        return this;
    }

    @Override
    public MediaInfo probe(String url) throws IOException {
        if (refusal != null) {
            throw refusal;
        }
        return answer;
    }

    @Override
    public List<PlaylistItem> playlistItems(String url, int limit) throws IOException {
        if (refusal != null) {
            throw refusal;
        }
        return entries.size() <= limit ? entries : entries.subList(0, limit);
    }
}
