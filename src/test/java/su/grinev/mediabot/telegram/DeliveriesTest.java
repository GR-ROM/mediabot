package su.grinev.mediabot.telegram;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import su.grinev.mediabot.Fixtures;
import su.grinev.mediabot.jobs.Job;
import su.grinev.mediabot.jobs.JobFinished;
import su.grinev.mediabot.jobs.JobKind;
import su.grinev.mediabot.jobs.JobState;
import su.grinev.mediabot.links.ShortLink;
import su.grinev.mediabot.links.ShortLinkService;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Telling a chat what became of its job.
 *
 * <p>Everything the bot says without being asked: the file or the link when it worked, and the
 * sentence explaining it when it did not. Every outcome is a template — there is nothing here worth
 * a round trip to a model, and nothing a model could add that it would not eventually get wrong.
 */
class DeliveriesTest {

    private static final long CHAT = 600000002L;

    private final MediaBot bot = mock(MediaBot.class);
    private final ShortLinkService shortLinks = mock(ShortLinkService.class);
    private final Deliveries deliveries =
            new Deliveries(bot, shortLinks, Fixtures.builder()
                    .uploads(50L * 1024 * 1024, 2L * 1024 * 1024 * 1024).build(), alerts());

    /**
     * The result path is what says "there is something to hand over"; how many files there turn out
     * to be is the link service's answer, which is mocked per test. Only the store may set the full
     * list, and that is the right place for it to be settable from.
     */
    private static Job job(JobState state, long sizeBytes, Path result) {
        return new Job(1, CHAT, JobKind.DOWNLOAD, "https://x.test/a", null, null, state,
                Instant.now(), Instant.now(), "Me at the zoo", result, sizeBytes,
                state == JobState.FAILED ? "yt-dlp said no" : null);
    }

    private static Job job(JobState state, long sizeBytes) {
        return job(state, sizeBytes, null);
    }

    /**
     * Alerting the owner is its own subject, and its own test. Here it only has to exist, so a
     * failure that would reach it does not reach a null instead.
     */
    private static OwnerAlerts alerts() {
        return mock(OwnerAlerts.class);
    }

    private static ShortLink link(String code, String name, long sizeBytes) {
        return new ShortLink(code, 1, CHAT, Path.of("public", code, name), name, sizeBytes,
                Instant.now(), Instant.now().plusSeconds(3600));
    }

    private void published(ShortLink... links) throws IOException {
        when(shortLinks.publishAll(any())).thenReturn(List.of(links));
        when(shortLinks.shareAll(any())).thenReturn(List.of(links));
        when(shortLinks.urlOf(any()))
                .thenAnswer(call -> "http://test.local:1488/d/" + ((ShortLink) call.getArgument(0)).code());
        when(shortLinks.linkLifetime()).thenReturn(Duration.ofHours(1));
    }

    private String said() {
        ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
        verify(bot).say(anyLong(), written.capture());
        return written.getValue();
    }

    @Test
    void aSmallFileGoesIntoTheChatItself() throws IOException {
        published(link("abc", "video.mp4", 1024 * 1024));
        when(bot.sendFile(anyLong(), any(), anyString())).thenReturn(true);

        deliveries.onJobFinished(new JobFinished(job(JobState.DONE, 1024 * 1024,
                Path.of("work", "1", "video.mp4"))));

        verify(bot).sendFile(anyLong(), any(), anyString());
        verify(bot, never()).say(anyLong(), anyString());
    }

    @Test
    void somethingTooBigToTravelBecomesALink() throws IOException {
        published(link("abc", "video.mp4", 900L * 1024 * 1024));

        deliveries.onJobFinished(new JobFinished(job(JobState.DONE, 900L * 1024 * 1024,
                Path.of("work", "1", "video.mp4"))));

        verify(bot, never()).sendFile(anyLong(), any(), anyString());
        assertTrue(said().contains("/d/abc"), said());
        assertTrue(said().contains("900 MB"), said());
        assertTrue(said().contains("1 h"), said());
    }

    @Test
    void aFileTelegramRefusesFallsBackToTheLink() throws IOException {
        published(link("abc", "video.mp4", 1024 * 1024));
        // Telegram refuses for reasons no size check predicts — a codec it will not preview, a
        // container it does not know.
        when(bot.sendFile(anyLong(), any(), anyString())).thenReturn(false);

        deliveries.onJobFinished(new JobFinished(job(JobState.DONE, 1024 * 1024,
                Path.of("work", "1", "video.mp4"))));

        assertTrue(said().contains("/d/abc"), said());
    }

    @Test
    void twoCutsAreTwoLinksInOneMessage() throws IOException {
        published(link("one", "a.mp4", 800L * 1024 * 1024),
                link("two", "b.mp4", 700L * 1024 * 1024));

        deliveries.onJobFinished(new JobFinished(job(JobState.DONE, 1500L * 1024 * 1024,
                Path.of("work", "1", "a.mp4"))));

        // A job asked for once is answered once; two notifications would read as two downloads.
        verify(bot, times(1)).say(anyLong(), anyString());
        assertTrue(said().contains("2 files"), said());
        assertTrue(said().contains("1. http://test.local:1488/d/one"), said());
        assertTrue(said().contains("2. http://test.local:1488/d/two"), said());
        assertTrue(said().contains("links work"), said());
    }

    @Test
    void aJobThatCameOutTooBigExplainsItselfAndSuggestsWhatToDo() {
        deliveries.onJobFinished(new JobFinished(job(JobState.TOO_BIG, 3L * 1024 * 1024 * 1024)));

        assertTrue(said().contains("3.0 GB"), said());
        assertTrue(said().contains("Ask for a smaller height"), said());
    }

    @Test
    void aFailedJobSaysWhatWentWrong() {
        deliveries.onJobFinished(new JobFinished(job(JobState.FAILED, 0)));

        assertTrue(said().contains("failed"), said());
        assertTrue(said().contains("yt-dlp said no"), said());
    }

    @Test
    void anInterruptedJobSaysToAskAgain() {
        deliveries.onJobFinished(new JobFinished(job(JobState.INTERRUPTED, 0)));

        assertTrue(said().contains("interrupted"), said());
        assertTrue(said().contains("Send the link again"), said());
    }

    @Test
    void aJobStillPendingIsNotAnnouncedAtAll() {
        deliveries.onJobFinished(new JobFinished(job(JobState.PENDING, 0)));

        verify(bot, never()).say(anyLong(), anyString());
        verify(bot, never()).sendFile(anyLong(), any(), anyString());
    }

    @Test
    void aDoneJobWithNoFileIsALoggedFaultAndNotAMessage() {
        deliveries.onJobFinished(new JobFinished(job(JobState.DONE, 0)));

        verify(bot, never()).say(anyLong(), anyString());
    }

    @Test
    void aFileThatCannotBePublishedIsSaidRatherThanSwallowed() throws IOException {
        when(shortLinks.publishAll(any())).thenThrow(new IOException("the disk is full"));

        deliveries.onJobFinished(new JobFinished(job(JobState.DONE, 1024,
                Path.of("work", "1", "video.mp4"))));

        assertTrue(said().contains("could not be published"), said());
        assertTrue(said().contains("disk is full"), said());
    }

    @Test
    void resendingHandsTheSameFileOverWithoutDoingTheWorkAgain() throws IOException {
        published(link("abc", "video.mp4", 1024 * 1024));

        deliveries.resend(CHAT, job(JobState.DONE, 1024 * 1024,
                Path.of("public", "abc", "video.mp4")));

        verify(shortLinks).shareAll(any());
        verify(shortLinks, never()).publishAll(any());
        assertTrue(said().contains("/d/abc"), said());
    }
}
