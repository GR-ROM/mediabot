package su.grinev.mediabot.links;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import su.grinev.mediabot.jobs.Job;
import su.grinev.mediabot.jobs.JobKind;
import su.grinev.mediabot.jobs.JobState;
import su.grinev.mediabot.telegram.MediaBot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The delivery path, over real HTTP.
 *
 * <p>What is being checked is the half a unit test cannot see: that the bytes come back whole, that
 * a player asking for the middle of the file gets the middle of the file rather than all of it, and
 * that a link nobody should have any more is a 404 with the file gone from the disk.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mediabot.llm.preflight=false",
        "mediabot.telegram.token=",
        "mediabot.loop.trace-path="
})
class LinkDeliveryTest {

    private static final byte[] CONTENT = content();

    @DynamicPropertySource
    static void freshDirectories(DynamicPropertyRegistry registry) {
        Path root = Path.of("build", "test-work", "links-" + System.nanoTime());
        registry.add("mediabot.jobs.database-path", () -> root.resolve("jobs.db").toString());
        registry.add("mediabot.media.work-dir", () -> root.resolve("media").toString());
        registry.add("mediabot.links.dir", () -> root.resolve("public").toString());
    }

    @MockitoBean
    private MediaBot bot;

    @Autowired
    private ShortLinkService shortLinks;

    @Autowired
    private ShortLinkRepository repository;

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void aPublishedFileComesBackWholeAndUnderItsOwnName() throws Exception {
        ShortLink link = shortLinks.publish(jobWithAFile(1, "Me at the zoo (240p).mp4"));

        HttpResponse<byte[]> response = get(url(link), null);

        assertEquals(200, response.statusCode());
        assertArrayEquals(CONTENT, response.body());
        assertEquals("video/mp4", header(response, "content-type"));
        assertTrue(header(response, "content-disposition").contains("Me%20at%20the%20zoo"),
                "the browser should offer the video's own name: "
                        + header(response, "content-disposition"));
        assertEquals("bytes", header(response, "accept-ranges"));
    }

    @Test
    void seekingAsksForOnePieceAndGetsOnePiece() throws Exception {
        ShortLink link = shortLinks.publish(jobWithAFile(2, "video.mp4"));

        HttpResponse<byte[]> response = get(url(link), "bytes=100-199");

        assertEquals(206, response.statusCode());
        assertEquals(100, response.body().length);
        byte[] expected = new byte[100];
        System.arraycopy(CONTENT, 100, expected, 0, 100);
        assertArrayEquals(expected, response.body());
    }

    @Test
    void aCodeNobodyIssuedIsA404() throws Exception {
        HttpResponse<byte[]> response =
                get("http://localhost:" + port + "/d/Zm9vYmFyYmF6cXV1eHh4", null);

        assertEquals(404, response.statusCode());
    }

    @Test
    void anExpiredLinkIsGoneAndSoIsItsFile() throws Exception {
        ShortLink published = shortLinks.publish(jobWithAFile(3, "old.mp4"));
        repository.deleteByCode(published.code());
        repository.save(new ShortLink(published.code(), published.jobId(), published.chatId(),
                published.file(), published.fileName(), published.sizeBytes(),
                Instant.now().minus(2, ChronoUnit.DAYS),
                Instant.now().minus(1, ChronoUnit.DAYS)));

        HttpResponse<byte[]> response = get(url(published), null);

        // Refused the moment it is overdue, whatever is still on disk: the row decides, and the
        // request from somebody's phone is not where a directory tree gets walked.
        assertEquals(404, response.statusCode());
        assertTrue(shortLinks.findActiveByCode(published.code()).isEmpty());

        shortLinks.deleteExpired();

        assertFalse(Files.exists(published.file()),
                "an expired link must take its file with it, or the disk fills up with videos "
                        + "nobody can reach any more");
    }

    private HttpResponse<byte[]> get(String url, String range) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url));
        if (range != null) {
            request.header("Range", range);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse("");
    }

    private String url(ShortLink link) {
        return "http://localhost:" + port + "/d/" + link.code();
    }

    private Job jobWithAFile(long id, String name) throws Exception {
        Path directory = Files.createTempDirectory("mediabot-link-");
        Path file = directory.resolve(name);
        Files.write(file, CONTENT);
        return new Job(id, 4242, JobKind.DOWNLOAD, "https://example.test/v", 240, null,
                JobState.DONE, Instant.now(), Instant.now(), "A video", file, CONTENT.length, null);
    }

    private static byte[] content() {
        byte[] bytes = new byte[4096];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i % 251);
        }
        return bytes;
    }
}
