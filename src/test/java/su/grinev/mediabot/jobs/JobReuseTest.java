package su.grinev.mediabot.jobs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import su.grinev.mediabot.db.Database;
import su.grinev.mediabot.graph.PipelineText;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fingerprint a repeated request is recognised by, and what may be handed back instead of
 * doing the work again.
 *
 * <p>Taken from the whole graph rather than the url, because the url is not the work: the same link
 * at two heights is two files, the same link with a cut is a third. Every step counts, and the
 * graph is the only place that has all of them.
 */
class JobReuseTest {

    private static final long CHAT = 1;
    private static final String URL = "https://www.youtube.com/watch?v=jNQXAC9IVRw";

    private Database database;
    private JobStore store;

    @BeforeEach
    void open(@TempDir Path directory) throws Exception {
        database = Database.inMemory();
        store = JobStore.on(database, 48);
    }

    @AfterEach
    void close() throws Exception {
        database.close();
    }

    @Test
    void theSameRequestHasTheSameFingerprint() {
        assertEquals(JobSpec.download(CHAT, URL, null).origin(),
                JobSpec.download(CHAT, URL, null).origin());
    }

    @Test
    void whoAskedAndWhatTheHostCallsItAreNotPartOfTheWork() {
        assertEquals(JobSpec.download(1, URL, null).origin(),
                JobSpec.download(2, URL, null).origin(),
                "who asked does not change what the file is; scoping is the store's business");
        assertEquals(JobSpec.download(CHAT, URL, null).origin(),
                JobSpec.download(CHAT, URL, null).withTitle("Me at the zoo").origin(),
                "a title arrives after probing and cannot change what was asked for");
    }

    @Test
    void everyStepAndParameterCountsTowardsTheFingerprint() {
        List<String> pipelines = List.of(
                "/download " + URL,
                "/download " + URL + " 480p",
                "/download " + URL + " /cut 1:00-2:00",
                "/download " + URL + " /cut 1:00-2:00 2:00-2:20",
                "/download " + URL + " /cut 1:00-2:00 /normalize",
                "/download " + URL + " /encode 480p",
                "/download " + URL + " /encode 720p");

        List<String> fingerprints = pipelines.stream()
                .map(written -> JobSpec.of(CHAT, PipelineText.read(written)).origin())
                .toList();

        assertEquals(pipelines.size(), fingerprints.stream().distinct().count(),
                "these are seven different files and must not share a fingerprint");
    }

    @Test
    void aFinishedJobIsHandedBackInsteadOfDoingTheWorkAgain() {
        JobSpec spec = JobSpec.download(CHAT, URL, null);
        Job first = store.create(spec);
        store.markDone(first.id(), JobState.DONE, List.of(Path.of("public", "abc", "video.mp4")), 1024);

        Job reused = store.findReusable(spec).orElseThrow(() ->
                new AssertionError("the same video with the same parameters is already on disk"));

        assertEquals(first.id(), reused.id());
        assertEquals(1, reused.results().size(), "and what comes back is the file it produced");
    }

    @Test
    void aJobStillRunningIsReusedToo() {
        JobSpec spec = JobSpec.download(CHAT, URL, null);
        Job first = store.create(spec);
        store.claimNext();

        assertEquals(first.id(), store.findReusable(spec).orElseThrow().id(),
                "queueing a second copy of work already under way is the same waste, earlier");
    }

    @Test
    void aFailedJobIsNotReused() {
        JobSpec spec = JobSpec.download(CHAT, URL, null);
        store.markFailed(store.create(spec).id(), JobState.FAILED, "yt-dlp said no");

        assertTrue(store.findReusable(spec).isEmpty(),
                "there is nothing to hand back, and refusing to retry would be worse");
    }

    @Test
    void anExpiredJobIsNotReused() {
        JobSpec spec = JobSpec.download(CHAT, URL, null);
        Job first = store.create(spec);
        store.markDone(first.id(), JobState.DONE, List.of(Path.of("public", "abc", "video.mp4")), 1);
        store.markState(first.id(), JobState.EXPIRED);

        assertTrue(store.findReusable(spec).isEmpty(),
                "the link is gone and so is the file; this is a job to do again");
    }

    @Test
    void reuseDoesNotCrossChats() {
        JobSpec mine = JobSpec.download(1, URL, null);
        Job first = store.create(mine);
        store.markDone(first.id(), JobState.DONE, List.of(Path.of("public", "abc", "video.mp4")), 1);

        assertTrue(store.findReusable(JobSpec.download(2, URL, null)).isEmpty(),
                "a link is minted for a chat; handing another chat's link over would leak it");
    }

    @Test
    void differentParametersAreNotReused() {
        JobSpec plain = JobSpec.download(CHAT, URL, null);
        Job first = store.create(plain);
        store.markDone(first.id(), JobState.DONE, List.of(Path.of("public", "abc", "video.mp4")), 1);

        assertNotEquals(plain.origin(), JobSpec.download(CHAT, URL, 480).origin());
        assertTrue(store.findReusable(JobSpec.download(CHAT, URL, 480)).isEmpty(),
                "480p is not the file that was downloaded at best quality");
    }

    @Test
    void theNewestReusableJobWins() {
        JobSpec spec = JobSpec.download(CHAT, URL, null);
        Job older = store.create(spec);
        Job newer = store.create(spec);
        store.markDone(older.id(), JobState.DONE, List.of(Path.of("public", "a", "video.mp4")), 1);
        store.markDone(newer.id(), JobState.DONE, List.of(Path.of("public", "b", "video.mp4")), 1);

        assertEquals(newer.id(), store.findReusable(spec).orElseThrow().id(),
                "the newest is the one whose link has the longest left to live");
    }

    @Test
    void aFinishedJobWithNothingToShowForItIsNotHandedBack() {
        JobSpec spec = JobSpec.download(CHAT, URL, null);
        Job first = store.create(spec);
        store.markDone(first.id(), JobState.DONE, List.of(), 0);

        assertFalse(store.findReusable(spec).isPresent(),
                "done and empty is not an answer to give somebody");
    }
}
