package su.grinev.mediabot.jobs;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import su.grinev.mediabot.media.DownloadProgress;
import su.grinev.mediabot.media.VideoDownloader;
import su.grinev.mediabot.telegram.MediaBot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which of the two ways a finished file leaves.
 *
 * <p>Small enough and it goes into the chat as a file, because a video you can watch where it landed
 * beats a link you have to open. Above that it goes as a link, which is the only way a download
 * larger than the Bot API will carry can be handed over at all.
 */
@SpringBootTest(properties = {
        "mediabot.llm.preflight=false",
        "mediabot.telegram.token=",
        "mediabot.jobs.concurrency=1",
        "mediabot.telegram.send-file-under-bytes=1048576"
})
class FileDeliveryE2ETest {

    private static final long CHAT = 909;
    private static final long LIMIT = 1024 * 1024;

    private static Path root;

    @DynamicPropertySource
    static void freshWorkingDirectories(DynamicPropertyRegistry registry) {
        root = Path.of("build", "test-work", "delivery-" + System.nanoTime());
        registry.add("mediabot.jobs.database-path", () -> root.resolve("jobs.db").toString());
        registry.add("mediabot.media.work-dir", () -> root.resolve("media").toString());
        registry.add("mediabot.links.dir", () -> root.resolve("public").toString());
        registry.add("mediabot.links.base-url", () -> "http://test.local:8080");
    }

    @MockitoBean
    private VideoDownloader downloader;

    @MockitoBean
    private MediaBot bot;

    @Autowired
    private JobQueue queue;

    @Test
    void aFileUnderTheLimitGoesIntoTheChatItself() throws Exception {
        produces(64 * 1024);
        when(bot.sendFile(anyLong(), any(), any())).thenReturn(true);

        queue.enqueue(JobSpec.download(CHAT, "https://example.test/small", 360).withTitle("Small"));

        var sent = ArgumentCaptor.forClass(Path.class);
        verify(bot, timeout(TimeUnit.SECONDS.toMillis(15)))
                .sendFile(eq(CHAT), sent.capture(), any());
        assertTrue(Files.isRegularFile(sent.getValue()), "the file handed over is not on disk");
        assertEquals(64 * 1024, Files.size(sent.getValue()));

        verify(bot, after(TimeUnit.SECONDS.toMillis(2)).never()).say(eq(CHAT), linkText());
    }

    @Test
    void aFileOverTheLimitGoesAsALink() throws Exception {
        produces((int) LIMIT + 1);

        queue.enqueue(JobSpec.download(CHAT, "https://example.test/big", 360).withTitle("Big"));

        verify(bot, timeout(TimeUnit.SECONDS.toMillis(15))).say(eq(CHAT), linkText());
        verify(bot, never()).sendFile(anyLong(), any(), any());
    }

    @Test
    void aFileTelegramRefusesFallsBackToItsLink() throws Exception {
        produces(64 * 1024);
        // The size said it would fit and the transport disagreed, which no check here can predict.
        when(bot.sendFile(anyLong(), any(), any())).thenReturn(false);

        queue.enqueue(JobSpec.download(CHAT, "https://example.test/refused", 360).withTitle("No"));

        verify(bot, timeout(TimeUnit.SECONDS.toMillis(15))).sendFile(eq(CHAT), any(), any());
        verify(bot, timeout(TimeUnit.SECONDS.toMillis(10))).say(eq(CHAT), linkText());
    }

    private static String linkText() {
        return org.mockito.ArgumentMatchers.argThat(
                text -> text != null && text.contains("http://test.local:8080/d/"));
    }

    private void produces(int bytes) throws Exception {
        when(downloader.video(any(), any(), any(), any(), any())).thenAnswer(call -> {
            Path directory = call.getArgument(2);
            DownloadProgress progress = call.getArgument(3);
            Path file = directory.resolve("video.mp4");
            Files.write(file, new byte[bytes]);
            progress.downloading(1.0);
            progress.processing(1.0);
            return file;
        });
    }
}
