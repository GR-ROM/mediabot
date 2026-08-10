package su.grinev.mediabot.telegram;

import org.junit.jupiter.api.Test;
import su.grinev.mediabot.jobs.Job;
import su.grinev.mediabot.jobs.JobKind;
import su.grinev.mediabot.jobs.JobState;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One message per job, rewritten as it goes.
 *
 * <p>The throttling is the point and not a nicety: Telegram rate-limits edits, and a download
 * reporting every percent would issue a hundred of them, get itself limited, and then fail to
 * deliver the messages that actually matter.
 */
class ProgressReporterTest {

    private static final long CHAT = 600000002L;

    private final InMemoryBot bot = new InMemoryBot();
    private final ProgressReporter reporter = new ProgressReporter(bot);

    private static Job job(JobState state, long sizeBytes, String title) {
        return new Job(1, CHAT, JobKind.DOWNLOAD, "https://www.youtube.com/watch?v=jNQXAC9IVRw",
                null, null, state, Instant.now(), Instant.now(), title, null, sizeBytes, null);
    }

    private Job started(String title) {
        Job job = job(JobState.DOWNLOADING, 0, title);
        reporter.started(job);
        return job;
    }

    @Test
    void aJobGetsOneMessageAndThenEditsOfIt() {
        Job job = started("Me at the zoo");

        reporter.progress(job, 0.4);

        assertEquals(1, bot.messages().size(), "one message per job");
        assertEquals(1, bot.editsTo(1), "and the rest are edits of it");
        assertEquals(1, reporter.messageFor(job.id()).orElseThrow());
        assertTrue(bot.textOf(1).contains("40%"), bot.textOf(1));
        assertTrue(bot.textOf(1).contains("Me at the zoo"), bot.textOf(1));
    }

    @Test
    void editsThatComeTooCloseTogetherAreDropped() {
        Job job = started("Me at the zoo");

        for (int i = 0; i < 20; i++) {
            reporter.progress(job, i / 20.0);
        }

        // One, not none: the first report goes out straight away so the bar appears, and the
        // throttle starts from there. The other nineteen are what would get the bot rate-limited.
        assertEquals(1, bot.editsTo(1));
    }

    @Test
    void aStepTooSmallToSeeIsNotWorthAnEdit() {
        Job job = started("Me at the zoo");
        reporter.progress(job, 0.5);

        reporter.progress(job, 0.51);

        assertEquals(1, bot.editsTo(1), "below the step the bar looks busy and says nothing");
    }

    @Test
    void progressForAJobNobodyAnnouncedIsIgnored() {
        // A job whose message never sent, or one from before this reporter existed. Editing a
        // message that was never sent is what the in-memory bot refuses outright.
        reporter.progress(job(JobState.DOWNLOADING, 0, "orphan"), 0.5);

        assertTrue(bot.saidNothing());
    }

    @Test
    void aJobWhoseMessageNeverArrivedIsNotTracked() {
        bot.mute();
        Job job = job(JobState.DOWNLOADING, 0, "Me at the zoo");
        reporter.started(job);

        reporter.progress(job, 0.9);

        // Somebody who blocked the bot: there is no message to edit, and editing message 0 would be
        // an error per progress callback.
        assertTrue(bot.saidNothing());
        assertTrue(reporter.messageFor(job.id()).isEmpty());
    }

    @Test
    void theProgressLineBecomesTheOutcome() {
        started("Me at the zoo");

        reporter.finished(job(JobState.DONE, 5L * 1024 * 1024, "Me at the zoo"));

        assertTrue(bot.textOf(1).contains("5 MB"), bot.textOf(1));
        assertTrue(bot.textOf(1).contains("Me at the zoo"), bot.textOf(1));
        assertEquals(1, bot.messages().size(), "and nothing new is posted under it");
    }

    @Test
    void everyOutcomeSaysWhichOneItIs() {
        for (var each : List.of(
                new Object[]{JobState.TOO_BIG, "too large"},
                new Object[]{JobState.FAILED, "❌"},
                new Object[]{JobState.CANCELLED, "cancelled"})) {

            InMemoryBot bot = new InMemoryBot();
            ProgressReporter reporter = new ProgressReporter(bot);
            reporter.started(job(JobState.DOWNLOADING, 0, "Me at the zoo"));

            reporter.finished(job((JobState) each[0], 3L * 1024 * 1024 * 1024, "Me at the zoo"));

            assertTrue(bot.textOf(1).contains((String) each[1]), each[0] + ": " + bot.textOf(1));
        }
    }

    @Test
    void aFinishedJobStopsBeingTracked() {
        Job job = started("Me at the zoo");

        reporter.finished(job(JobState.DONE, 1024, "Me at the zoo"));
        reporter.progress(job, 0.95);
        reporter.finished(job(JobState.DONE, 1024, "Me at the zoo"));

        // One edit for the outcome, and nothing after: a late progress callback from a job that has
        // already been answered must not reopen its line.
        assertEquals(1, bot.editsTo(1));
        assertTrue(reporter.messageFor(job.id()).isEmpty());
    }

    @Test
    void aLongTitleIsCutSoTheLineIsReadAtAGlance() {
        Job job = started("very long title ".repeat(10));

        reporter.progress(job, 0.5);

        assertTrue(bot.textOf(1).length() < 80, bot.textOf(1));
        assertTrue(bot.textOf(1).endsWith("…"), bot.textOf(1));
    }

    @Test
    void theBarFillsWithTheFraction() {
        Job job = started("Me at the zoo");

        reporter.progress(job, 1.0);

        assertTrue(bot.textOf(1).startsWith("▰▰▰▰▰▰▰▰▰▰"), bot.textOf(1));
        assertTrue(bot.textOf(1).contains("100%"), bot.textOf(1));
    }
}
