package su.grinev.mediabot.telegram;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import su.grinev.mediabot.AgentProperties;
import su.grinev.mediabot.Fixtures;
import su.grinev.mediabot.access.AccessList;
import su.grinev.mediabot.access.AccessLists;
import su.grinev.mediabot.db.Database;
import su.grinev.mediabot.jobs.DownloadRequests;
import su.grinev.mediabot.jobs.Job;
import su.grinev.mediabot.jobs.JobQueue;
import su.grinev.mediabot.jobs.JobSpec;
import su.grinev.mediabot.jobs.JobState;
import su.grinev.mediabot.jobs.JobStore;
import su.grinev.mediabot.jobs.JobStores;
import su.grinev.mediabot.jobs.JobWorkers;
import su.grinev.mediabot.links.ShortLinkService;
import su.grinev.mediabot.llm.IntentParser;
import su.grinev.mediabot.llm.PipelineUpgrade;
import su.grinev.mediabot.media.InMemoryYtDlp;
import su.grinev.mediabot.media.MediaStore;
import su.grinev.mediabot.media.ProbeCache;
import su.grinev.mediabot.media.UrlGuard;
import su.grinev.mediabot.route.RequestRouter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Everything a chat message can cause, and nothing it cannot.
 *
 * <p>Run against the real thing wherever the real thing can run: the router, the access list, the
 * queue and its store, the url guard and the refusals all behave here as they do in the deployed
 * bot, on a database that lives in this process. What is written out instead is the chat, which is
 * a network, and the host, which is somebody else's website.
 *
 * <p>That is what makes these worth having. A message ends in a row in a table, and a test that
 * stubbed the queue would have asserted on its own stubbing — which is precisely how the agent that
 * used to sit on this path announced jobs that did not exist.
 */
class ChatDispatcherTest {

    private static final long OWNER = 500000001L;
    private static final long GUEST = 600000002L;
    private static final String URL = "https://www.youtube.com/watch?v=jNQXAC9IVRw";

    private Database database;
    private JobStore store;
    private JobQueue queue;
    private InMemoryBot bot;
    private InMemoryYtDlp ytDlp;
    private AccessList access;
    private RecordingDeliveries deliveries;
    private IntentParser parser;
    private PipelineUpgrade upgrade;
    private ChatDispatcher dispatcher;

    /** Delivery is its own subject; here it only has to be observable. */
    private static class RecordingDeliveries extends Deliveries {
        private final List<Long> resent = new java.util.ArrayList<>();
        private IOException failWith;

        RecordingDeliveries(MediaBot bot, AgentProperties props) {
            super(bot, mock(ShortLinkService.class), props, mock(OwnerAlerts.class));
        }

        @Override
        public void resend(long chatId, Job job) throws IOException {
            if (failWith != null) {
                throw failWith;
            }
            resent.add(job.id());
        }
    }

    @BeforeEach
    void setUp(@TempDir Path temp) throws Exception {
        AgentProperties props = Fixtures.builder()
                .owners(OWNER).allowedChats(GUEST)
                .workDir(temp.resolve("work"))
                .perChatLimit(8)
                .build();

        database = Database.inMemory();
        store = JobStores.on(database, 48);
        access = AccessLists.opened(database, props);
        bot = new InMemoryBot();
        ytDlp = new InMemoryYtDlp();

        MediaStore media = new MediaStore(props);
        JobWorkers workers = new JobWorkers(store, null, media, event -> {
        }, null, props);
        // Never started: this is about what reaches the table, not about what a worker does with it.
        queue = new JobQueue(store, workers, media, props);

        UrlGuard guard = new UrlGuard(props);
        DownloadRequests requests = new DownloadRequests(guard, new ProbeCache(ytDlp), queue);

        deliveries = new RecordingDeliveries(bot, props);
        parser = mock(IntentParser.class);
        upgrade = mock(PipelineUpgrade.class);
        when(parser.parse(anyLong(), anyString())).thenReturn(Optional.empty());
        when(upgrade.applyTo(anyLong(), anyString(), any())).thenAnswer(call -> call.getArgument(2));

        dispatcher = new ChatDispatcher(bot, access, new RequestRouter(), parser, upgrade, requests,
                queue, new AdminReplies(bot, access), deliveries, ytDlp, guard, props);
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    private Job onlyJob() {
        List<Job> queued = queue.recentIn(GUEST, 16);
        assertEquals(1, queued.size(), "want exactly one job in the table");
        return queued.getFirst();
    }

    // ---------------------------------------------------------------- access

    @Test
    void aStrangerIsDroppedWithoutAWord() {
        dispatcher.onMessage(999999999L, URL);

        assertTrue(bot.saidNothing(), "the bot gives away nothing to somebody probing it");
        assertTrue(queue.recentIn(999999999L, 16).isEmpty());
    }

    // ---------------------------------------------------------------- fetching

    @Test
    void aBareLinkBecomesARowAndAnAnswer() {
        dispatcher.onMessage(GUEST, URL);

        Job job = onlyJob();
        assertEquals(JobState.PENDING, job.state());
        assertEquals(URL, job.url());
        assertTrue(bot.said().contains("Queued as job " + job.id()), bot.said());
        assertTrue(bot.said().contains("About"), "the probe gave a size, so it is quoted");
    }

    @Test
    void theHeightCeilingIsAppliedOnTheWayIntoTheQueueAndSaidOutLoud() {
        dispatcher.onMessage(GUEST, URL + " 1080p");

        assertEquals(720, onlyJob().maxHeight(), "capped in the row, not only in the message");
        assertTrue(bot.said().contains("Capped at 720p"), bot.said());
    }

    @Test
    void anOwnerIsNotCappedToWhatGuestsGet() {
        dispatcher.onMessage(OWNER, URL + " 1080p");

        assertEquals(1080, queue.recentIn(OWNER, 16).getFirst().maxHeight());
        assertFalse(bot.said().contains("Capped"), bot.said());
    }

    @Test
    void aHeightTheSourceDoesNotHaveIsRefusedRatherThanQueued() {
        ytDlp.publishing(InMemoryYtDlp.video(360));

        dispatcher.onMessage(OWNER, URL + " 1080p");

        assertTrue(bot.said().contains("I cannot do that"), bot.said());
        assertTrue(bot.said().contains("tops out at 360p"), bot.said());
        assertTrue(queue.recentIn(OWNER, 16).isEmpty(), "and nothing was queued");
    }

    @Test
    void aPlainDownloadOfASmallVideoIsNotRefusedByTheCeilingPutOnIt() {
        // The bug this exists for: a bare link is capped on the way in, and comparing the capped
        // height against a 360p source refused the commonest request there is.
        ytDlp.publishing(InMemoryYtDlp.video(360));

        dispatcher.onMessage(GUEST, URL);

        assertEquals(JobState.PENDING, onlyJob().state(), bot.said());
    }

    @Test
    void aLiveStreamIsRefusedBeforeAWorkerCanSitOnIt() {
        ytDlp.streaming();

        dispatcher.onMessage(GUEST, URL);

        assertTrue(bot.said().contains("live stream"), bot.said());
        assertTrue(queue.recentIn(GUEST, 16).isEmpty());
    }

    @Test
    void aHostTheBotWillNotFetchFromNeverReachesTheQueue() {
        dispatcher.onMessage(GUEST, "https://vk.com/video1");

        assertTrue(bot.said().contains("I cannot do that"), bot.said());
        assertTrue(bot.said().contains("vk.com"), bot.said());
        assertTrue(queue.recentIn(GUEST, 16).isEmpty());
    }

    @Test
    void oneChatCannotFillTheQueue() {
        for (int i = 0; i < 8; i++) {
            dispatcher.onMessage(GUEST, URL + "&i=" + i);
        }

        dispatcher.onMessage(GUEST, URL + "&i=last");

        assertTrue(bot.said().contains("limit (8)"), bot.said());
        assertEquals(8, queue.pendingIn(GUEST).size());
    }

    @Test
    void aChainReachesTheQueueWhole() {
        dispatcher.onMessage(GUEST, "/download " + URL + " /cut 1:00-2:00 2:00-2:20 /encode 480p");

        Job job = onlyJob();
        assertTrue(job.graph().nodes().size() >= 6,
                "a chain is one job however many files it makes");
        assertEquals(2, job.graph().outputs().size(), "two ranges are two files");
    }

    @Test
    void theSameRequestTwiceIsOneJob() {
        dispatcher.onMessage(GUEST, URL);
        dispatcher.onMessage(GUEST, URL);

        onlyJob();   // the second was answered from the first
    }

    // ---------------------------------------------------------------- refusals a person reads

    @Test
    void aRecognisedCommandWithoutItsArgumentsGetsItsOwnUsage() {
        dispatcher.onMessage(GUEST, "/download");

        assertTrue(bot.said().contains("/download"), bot.said());
        assertTrue(bot.said().contains("<link>"), bot.said());
        assertTrue(bot.said().length() < 200,
                "they said what they wanted; this is not the moment for the manual");
    }

    @Test
    void aChainThatDoesNotParseSaysWhatStoppedIt() {
        dispatcher.onMessage(GUEST, "/download " + URL + " /cut nonsense");

        assertTrue(bot.said().contains("I could not run that"), bot.said());
        assertTrue(queue.recentIn(GUEST, 16).isEmpty(),
                "never a download of whatever could be salvaged from it");
    }

    @Test
    void somethingUnreadableWithNoParserAnswerGetsTheManual() {
        dispatcher.onMessage(GUEST, "do you think this is any good?");

        assertTrue(bot.said().contains("I could not read that"), bot.said());
        assertTrue(bot.said().contains("/download"), bot.said());
    }

    @Test
    void aMessageTheRouterCannotReadIsOfferedToTheModel() {
        when(parser.parse(anyLong(), anyString()))
                .thenReturn(Optional.of(JobSpec.download(GUEST, URL, null)));

        dispatcher.onMessage(GUEST, "выровняй звук и достань видео отсюда");

        // The model's answer goes through the same queue, the same guard and the same refusals as
        // everything else.
        assertEquals(JobState.PENDING, onlyJob().state());
    }

    @Test
    void aModelAnswerPointingSomewhereElseIsStillRefused() {
        when(parser.parse(anyLong(), anyString())).thenReturn(
                Optional.of(JobSpec.download(GUEST, "https://evil.test/invented", null)));

        dispatcher.onMessage(GUEST, "достань это");

        assertTrue(bot.said().contains("I cannot do that"), bot.said());
        assertTrue(queue.recentIn(GUEST, 16).isEmpty());
    }

    // ---------------------------------------------------------------- status, link, cancel

    @Test
    void statusOnAChatThatHasNeverDownloadedAnything() {
        dispatcher.onMessage(GUEST, "/status");

        assertTrue(bot.said().contains("Nothing has been downloaded in this chat yet"), bot.said());
    }

    @Test
    void statusListsWhatHappened() {
        dispatcher.onMessage(GUEST, URL);
        long jobId = onlyJob().id();
        store.markDone(jobId, JobState.DONE, List.of(Path.of("public", "abc", "video.mp4")),
                5L * 1024 * 1024);

        dispatcher.onMessage(GUEST, "/status");

        assertTrue(bot.said().contains("0 queued or running"), bot.said());
        assertTrue(bot.said().contains("#" + jobId + " done (5 MB)"), bot.said());
        assertTrue(bot.said().contains("Me at the zoo"), "the probed title, not the url");
    }

    @Test
    void linkWithNothingFinishedYet() {
        dispatcher.onMessage(GUEST, "/link");

        assertTrue(bot.said().contains("Nothing has finished downloading in this chat yet"),
                bot.said());
    }

    @Test
    void linkHandsTheSameFileOverAgainWithoutDoingTheWorkAgain() {
        dispatcher.onMessage(GUEST, URL);
        long jobId = onlyJob().id();
        store.markDone(jobId, JobState.DONE, List.of(Path.of("public", "abc", "video.mp4")), 1024);

        dispatcher.onMessage(GUEST, "/link " + jobId);

        assertEquals(List.of(jobId), deliveries.resent);
    }

    @Test
    void linkForAJobThatIsNotFinishedSaysSoRatherThanFailing() {
        dispatcher.onMessage(GUEST, URL);
        long jobId = onlyJob().id();

        dispatcher.onMessage(GUEST, "/link " + jobId);

        assertTrue(bot.said().contains("Job " + jobId + " is pending"), bot.said());
        assertTrue(bot.said().contains("no file yet"), bot.said());
    }

    @Test
    void linkForAJobInSomebodyElsesChatIsNotFound() {
        dispatcher.onMessage(OWNER, URL);
        long jobId = queue.recentIn(OWNER, 16).getFirst().id();

        dispatcher.onMessage(GUEST, "/link " + jobId);

        assertTrue(bot.said().contains("There is no job " + jobId + " in this chat"), bot.said());
    }

    @Test
    void linkWhenTheFileHasBeenSweptAwaySaysToAskAgain() {
        dispatcher.onMessage(GUEST, URL);
        long jobId = onlyJob().id();
        store.markDone(jobId, JobState.DONE, List.of(Path.of("public", "abc", "video.mp4")), 1024);
        deliveries.failWith = new IOException("no such file");

        dispatcher.onMessage(GUEST, "/link " + jobId);

        assertTrue(bot.said().contains("is gone"), bot.said());
        assertTrue(bot.said().contains("fetch it once more"), bot.said());
    }

    @Test
    void cancellingAJobStopsItInTheTable() {
        dispatcher.onMessage(GUEST, URL);
        long jobId = onlyJob().id();

        dispatcher.onMessage(GUEST, "/cancel " + jobId);

        assertTrue(bot.said().contains("Job " + jobId + " is cancelled"), bot.said());
        assertEquals(JobState.CANCELLED, store.find(jobId).orElseThrow().state());
    }

    @Test
    void cancellingSomethingThatAlreadyFinished() {
        dispatcher.onMessage(GUEST, URL);
        long jobId = onlyJob().id();
        store.markDone(jobId, JobState.DONE, List.of(Path.of("a.mp4")), 1);

        dispatcher.onMessage(GUEST, "/cancel " + jobId);

        assertTrue(bot.said().contains("had already finished"), bot.said());
    }

    @Test
    void cancellingAJobThatIsNotThere() {
        dispatcher.onMessage(GUEST, "/cancel 7");

        assertTrue(bot.said().contains("There is no job 7 in this chat"), bot.said());
    }

    // ---------------------------------------------------------------- help

    @Test
    void theHelpIsGeneratedFromTheCommandsThemselves() {
        dispatcher.onMessage(GUEST, "/help");

        for (String expected : List.of("/download", "/cut", "LOW", "mp4", "m4a")) {
            assertTrue(bot.said().contains(expected), expected + " is missing from the banner");
        }
        assertFalse(bot.said().contains("/allow"),
                "a guest has no business seeing the owner commands");
    }

    @Test
    void theOwnerGetsTheirOwnSection() {
        dispatcher.onMessage(OWNER, "/help");

        assertTrue(bot.said().contains("/allow"), bot.said());
        assertTrue(bot.said().contains("Yours only"), bot.said());
    }

    // ---------------------------------------------------------------- playlists

    @Test
    void aPlaylistQueuesEachVideoAndSaysHowMany() {
        ytDlp.withPlaylist("One", "Two");

        dispatcher.onMessage(GUEST, "/playlist " + URL);

        assertEquals(2, queue.pendingIn(GUEST).size());
        assertTrue(bot.said().contains("Queued 2 of 2"), bot.said());
    }

    @Test
    void oneVideoThatCannotBeHadIsNotAReasonToAbandonTheOthers() {
        ytDlp.withPlaylist("One", "Two", "Three");
        for (int i = 0; i < 7; i++) {
            dispatcher.onMessage(GUEST, URL + "&filler=" + i);
        }

        dispatcher.onMessage(GUEST, "/playlist " + URL);

        // Room for one more, and the other two are refused by name rather than silently dropped.
        assertTrue(bot.said().contains("Queued 1 of 3"), bot.said());
        assertTrue(bot.said().contains("Could not queue"), bot.said());
        assertTrue(bot.said().contains("Two"), bot.said());
    }

    @Test
    void aLinkWithNoPlaylistBehindItSaysSo() {
        dispatcher.onMessage(GUEST, "/playlist " + URL);

        assertTrue(bot.said().contains("Nothing that looks like a playlist"), bot.said());
    }

    @Test
    void aPlaylistThatCannotBeReadSaysWhy() {
        ytDlp.refusing("the playlist is private");

        dispatcher.onMessage(GUEST, "/playlist " + URL);

        assertTrue(bot.said().contains("could not read that playlist"), bot.said());
    }

    // ---------------------------------------------------------------- administration

    @Test
    void anOwnerLettingSomebodyInTakesEffectImmediately() {
        long newcomer = 123456789L;
        assertFalse(access.permits(newcomer));

        dispatcher.onMessage(OWNER, "/allow " + newcomer + " works with me");

        assertTrue(access.permits(newcomer), "no restart needed; that is why the list is a table");
        assertTrue(bot.said().contains("can use the bot now"), bot.said());
    }

    @Test
    void aGuestWritingAnOwnerCommandIsRefusedRatherThanObeyed() {
        dispatcher.onMessage(GUEST, "/allow 123456789");

        assertTrue(bot.said().contains("not yours to do"), bot.said());
        assertFalse(access.permits(123456789L));
    }
}
