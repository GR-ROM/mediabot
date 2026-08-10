package su.grinev.mediabot.telegram;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import su.grinev.mediabot.Fixtures;
import su.grinev.mediabot.access.AccessList;
import su.grinev.mediabot.access.AccessLists;
import su.grinev.mediabot.db.Database;
import su.grinev.mediabot.jobs.Job;
import su.grinev.mediabot.jobs.JobKind;
import su.grinev.mediabot.jobs.JobState;
import su.grinev.mediabot.media.YtDlp;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The failures the owner has to do something about, told to the owner.
 *
 * <p>Everything else the bot says goes to whoever asked. This one cannot: a person sent a link and
 * was told the host wants proof the bot is not a bot, which is true, unhelpful and not theirs to
 * fix. The person who can fix it would otherwise find out by somebody complaining.
 */
class OwnerAlertsTest {

    private static final long OWNER = 500000001L;
    private static final long SECOND_OWNER = 500000002L;
    private static final long GUEST = 600000002L;

    private Database database;
    private InMemoryBot bot;
    private AccessList access;
    private OwnerAlerts alerts;

    @BeforeEach
    void setUp() throws Exception {
        database = Database.inMemory();
        access = AccessLists.opened(database,
                Fixtures.builder().owners(OWNER, SECOND_OWNER).build());
        bot = new InMemoryBot();
        alerts = new OwnerAlerts(bot, access);
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    private static Job failedWith(String reason) {
        return new Job(7, GUEST, JobKind.DOWNLOAD, "https://www.youtube.com/watch?v=jNQXAC9IVRw",
                null, null, JobState.FAILED, Instant.now(), Instant.now(), null, null, 0, reason);
    }

    @Test
    void everyOwnerIsToldWhenTheBotCheckStartsFailing() {
        alerts.consider(failedWith(YtDlp.NEEDS_AUTHENTICATION));

        assertEquals(List.of(OWNER, SECOND_OWNER),
                bot.messages().stream().map(InMemoryBot.Sent::chatId).toList());
    }

    @Test
    void theMessageSaysWhatToLookAtRatherThanWhatHappened() {
        alerts.consider(failedWith(YtDlp.NEEDS_AUTHENTICATION));

        String said = bot.said();
        assertTrue(said.contains("job 7"), said);
        assertTrue(said.contains("potoken"), "the provider is the first thing to check: " + said);
        assertTrue(said.contains("cookies"), "and the fallback is the second: " + said);
    }

    @Test
    void whoeverSentTheLinkIsNotToldTwice() {
        alerts.consider(failedWith(YtDlp.NEEDS_AUTHENTICATION));

        assertTrue(bot.messages().stream().noneMatch(sent -> sent.chatId() == GUEST),
                "the deliveries already told them, and it is not theirs to fix");
    }

    @Test
    void aStaleCredentialIsOneMessageRatherThanOnePerJob() {
        for (int i = 0; i < 20; i++) {
            alerts.consider(failedWith(YtDlp.NEEDS_AUTHENTICATION));
        }

        // Every job fails on the same thing, and a message per failure would be its own outage.
        assertEquals(2, bot.messages().size(), "one message per owner, not twenty");
    }

    @Test
    void everyOtherFailureIsNoneOfTheOwnersBusiness() {
        for (String reason : List.of(
                "the video is private — no downloader can get it",
                "the video has been removed or is unavailable",
                "this video does not have the quality that was asked for",
                "yt-dlp exited with an error")) {

            alerts.consider(failedWith(reason));
        }

        assertTrue(bot.saidNothing(), "these are the asker's problem, or nobody's");
    }

    @Test
    void aJobWithNothingToSayIsNotAnAlert() {
        alerts.consider(failedWith(null));
        alerts.consider(null);

        assertTrue(bot.saidNothing());
    }

    @Test
    void aBotWithNoOwnersConfiguredSaysNothingRatherThanFailing() throws Exception {
        Database empty = Database.inMemory();
        try {
            InMemoryBot bot = new InMemoryBot();
            OwnerAlerts alerts = new OwnerAlerts(bot,
                    AccessLists.opened(empty, Fixtures.builder().build()));

            alerts.consider(failedWith(YtDlp.NEEDS_AUTHENTICATION));

            assertTrue(bot.saidNothing());
        } finally {
            empty.close();
        }
    }
}
