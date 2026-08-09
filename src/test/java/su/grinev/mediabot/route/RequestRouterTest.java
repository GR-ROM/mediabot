package su.grinev.mediabot.route;

import org.junit.jupiter.api.Test;
import su.grinev.mediabot.jobs.JobKind;
import su.grinev.mediabot.jobs.JobSpec;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole of the bot's understanding, now that there is no model on the message path.
 *
 * <p>Every case here used to cost a round trip to a language model, and two of them — the invented
 * job number and the announced download that was never queued — are things the model got wrong in
 * production. A regex cannot be confidently wrong about what it did not match.
 */
class RequestRouterTest {

    private static final long CHAT = 1;
    private static final String URL = "https://www.youtube.com/watch?v=jNQXAC9IVRw";

    private final RequestRouter router = new RequestRouter();

    @Test
    void aBareLinkIsADownloadAtTheBestQuality() {
        var spec = fetch(URL);

        assertEquals(JobKind.DOWNLOAD, spec.scenario());
        assertEquals(URL, spec.url());
        assertNull(spec.maxHeight());
    }

    @Test
    void aHeightIsTakenFromTheWordsAroundTheLink() {
        assertEquals(720, fetch(URL + " 720p").maxHeight());
        assertEquals(1080, fetch("in 1080 " + URL).maxHeight());
        assertEquals(2160, fetch(URL + " in 4k").maxHeight());
        assertEquals(480, fetch("download " + URL + " 480п").maxHeight(),
                "a Russian keyboard layout produces 480п constantly; that is a layout, not a word");
    }

    @Test
    void askingForTheSoundIsADifferentScenario() {
        assertEquals(JobKind.AUDIO, fetch("only the audio from " + URL).scenario());
        assertEquals(JobKind.AUDIO, fetch("rip the sound out of " + URL).scenario());
        assertEquals("mp3", fetch(URL + " as mp3").audioFormat());
        assertEquals("m4a", fetch("just the track " + URL).audioFormat(),
                "no container asked for is m4a, decided once in JobSpec");
    }

    @Test
    void askingToReEncodeIsAThirdScenario() {
        var spec = fetch("re-encode " + URL + " to 480p");

        assertEquals(JobKind.TRANSCODE, spec.scenario());
        assertEquals(480, spec.maxHeight());
        assertEquals(720, fetch("compress " + URL).maxHeight(),
                "a transcode with no height still knows what it is encoding to");
    }

    @Test
    void aPlaylistIsCountedRatherThanGuessedAt() {
        var request = router.parse(CHAT, "first 3 from this playlist " + URL).orElseThrow();

        Request.Playlist playlist = assertInstanceOf(Request.Playlist.class, request);
        assertEquals(3, playlist.count());

        var withoutCount = (Request.Playlist) router.parse(CHAT, "playlist " + URL).orElseThrow();
        assertEquals(5, withoutCount.count(), "a default, not an interpretation");
    }

    @Test
    void aHeightIsNotMistakenForAPlaylistCount() {
        var playlist = (Request.Playlist)
                router.parse(CHAT, "playlist " + URL + " 1080p").orElseThrow();

        assertEquals(1080, playlist.maxHeight());
        assertEquals(5, playlist.count());
    }

    @Test
    void everySlashCommandIsRead() {
        assertEquals(JobKind.DOWNLOAD, fetch("/download " + URL).scenario());
        assertEquals(JobKind.AUDIO, fetch("/audio " + URL).scenario());
        assertEquals(JobKind.TRANSCODE, fetch("/transcode " + URL).scenario());
        assertInstanceOf(Request.Playlist.class,
                router.parse(CHAT, "/playlist " + URL).orElseThrow());
        assertInstanceOf(Request.Status.class, router.parse(CHAT, "/status").orElseThrow());
        assertInstanceOf(Request.Help.class, router.parse(CHAT, "/help").orElseThrow());
        assertEquals(7L, ((Request.Cancel) router.parse(CHAT, "/cancel 7").orElseThrow()).jobId());
        assertNull(((Request.Link) router.parse(CHAT, "/link").orElseThrow()).jobId());
        assertEquals(3L, ((Request.Link) router.parse(CHAT, "/link 3").orElseThrow()).jobId());
    }

    @Test
    void aSlashCommandTakesTheSameArgumentsAsTheWords() {
        assertEquals(720, fetch("/download 720p " + URL).maxHeight());
        assertEquals("mp3", fetch("/audio mp3 " + URL).audioFormat());
        assertEquals(480, fetch("/transcode 480p " + URL).maxHeight());

        var playlist = (Request.Playlist)
                router.parse(CHAT, "/playlist 3 720p " + URL).orElseThrow();
        assertEquals(3, playlist.count());
        assertEquals(720, playlist.maxHeight());
    }

    @Test
    void argumentOrderIsFree() {
        assertEquals(720, fetch("/download " + URL + " 720p").maxHeight());
        assertEquals(720, fetch("/download 720p " + URL).maxHeight());
        assertEquals("mp3", fetch("/audio " + URL + " mp3").audioFormat());
    }

    @Test
    void smallIsTheShorthandForDownloadThenEncodeLow() {
        var spec = fetch("/small " + URL);

        assertEquals(JobKind.TRANSCODE, spec.scenario());
        assertEquals(360, spec.maxHeight(), "LOW brings its own height");
        assertEquals(3, spec.graph().nodes().size(), "fetch, encode, publish");

        assertEquals(480, fetch("/small 480p " + URL).maxHeight(),
                "a height next to it wins, as it does inside /encode");
    }

    @Test
    void theSameCommandsAskedInWordsReachTheSamePlace() {
        assertInstanceOf(Request.Status.class, router.parse(CHAT, "what's running?").orElseThrow());
        assertInstanceOf(Request.Status.class, router.parse(CHAT, "is it done yet").orElseThrow());
        assertInstanceOf(Request.Link.class, router.parse(CHAT, "I lost the link").orElseThrow());
        assertInstanceOf(Request.Help.class, router.parse(CHAT, "what can you do?").orElseThrow());
        assertEquals(4L, ((Request.Cancel) router.parse(CHAT, "cancel job 4").orElseThrow()).jobId());
    }

    @Test
    void aNamedCommandBeatsWhatTheWordsWouldHaveGuessed() {
        assertEquals(JobKind.DOWNLOAD, fetch("/download the sound of it " + URL).scenario(),
                "asked for by name, so the audio stems do not get a vote");
        assertEquals(JobKind.AUDIO, fetch("/audio compress it " + URL).scenario());
    }

    @Test
    void aNamedCommandWithoutItsArgumentsSaysWhatItTakes() {
        // The failure that sent people the whole manual: a command the bot knows perfectly well,
        // written without what it needs, used to be indistinguishable from gibberish.
        var noLink = assertThrows(Command.Incomplete.class, () -> router.parse(CHAT, "/download"));
        assertTrue(noLink.getMessage().contains("/download"), noLink.getMessage());
        assertTrue(noLink.getMessage().contains("<link>"), noLink.getMessage());

        var noJob = assertThrows(Command.Incomplete.class, () -> router.parse(CHAT, "/cancel"));
        assertTrue(noJob.getMessage().contains("<job>"), noJob.getMessage());

        var noChat = assertThrows(Command.Incomplete.class, () -> router.parse(CHAT, "/allow"));
        assertTrue(noChat.getMessage().contains("<chat id>"), noChat.getMessage());
    }

    @Test
    void aCommandThatDoesNotExistIsStillNotGuessedAt() {
        assertTrue(router.parse(CHAT, "/nonsense").isEmpty());
        assertTrue(router.parse(CHAT, "/quit").isEmpty(), "the console owns this one");
    }

    @Test
    void aMessageItCannotReadIsNotGuessedAt() {
        assertTrue(router.parse(CHAT, "do you think this is any good?").isEmpty());
        assertTrue(router.parse(CHAT, "who directed this").isEmpty());
        assertTrue(router.parse(CHAT, "").isEmpty());
    }

    @Test
    void russianIsNoLongerReadLiterallyAndFallsThroughToTheParser() {
        assertTrue(router.parse(CHAT, "что качается?").isEmpty());
        assertTrue(router.parse(CHAT, "отмени 4").isEmpty());
        assertTrue(router.parse(CHAT, "я потерял ссылку").isEmpty());
    }

    @Test
    void aLinkAtTheEndOfASentenceDoesNotCollectTheFullStop() {
        assertEquals(URL, fetch("download " + URL + ".").url());
        assertEquals(URL, fetch("(" + URL + ")").url());
    }

    private JobSpec fetch(String text) {
        Optional<Request> request = router.parse(CHAT, text);
        return ((Request.Fetch) request.orElseThrow()).spec();
    }
}
