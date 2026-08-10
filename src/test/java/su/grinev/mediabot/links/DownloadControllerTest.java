package su.grinev.mediabot.links;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The other half of a link: what happens when somebody clicks it.
 *
 * <p>These are videos, so the interesting case is not "the file comes back" but "the player asked
 * for the middle and got only the middle" — without that a two-gigabyte file has to be downloaded
 * in full before anything can be seen at a minute in.
 */
class DownloadControllerTest {

    private final ShortLinkService shortLinks = mock(ShortLinkService.class);
    private final DownloadController controller = new DownloadController(shortLinks);

    private ShortLink published(Path directory, String name, byte[] content) throws IOException {
        Path file = directory.resolve(name);
        Files.write(file, content);
        ShortLink link = new ShortLink("abc123", 1, 600000002L, file, name, content.length,
                Instant.now(), Instant.now().plusSeconds(3600));
        when(shortLinks.findActiveByCode("abc123")).thenReturn(Optional.of(link));
        return link;
    }

    private static byte[] bodyOf(ResponseEntity<StreamingResponseBody> response) throws IOException {
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        response.getBody().writeTo(written);
        return written.toByteArray();
    }

    @Test
    void aClickOnALiveLinkStreamsTheFile(@TempDir Path temp) throws IOException {
        published(temp, "video.mp4", "0123456789abcdef".getBytes());

        var response = controller.download("abc123", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("0123456789abcdef", new String(bodyOf(response)));
        assertEquals(16, response.getHeaders().getContentLength());
    }

    @Test
    void rangesAreAdvertisedSoAPlayerKnowsItMayAsk(@TempDir Path temp) throws IOException {
        published(temp, "video.mp4", "0123456789".getBytes());

        var response = controller.download("abc123", null);

        assertEquals("bytes", response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES),
                "a player that is not told it may seek will download the whole file to seek");
    }

    @Test
    void aPlayerAsksForTheMiddleAndGetsOnlyThat(@TempDir Path temp) throws IOException {
        published(temp, "video.mp4", "0123456789abcdef".getBytes());

        var response = controller.download("abc123", "bytes=4-7");

        assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
        assertEquals("bytes 4-7/16", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        assertEquals(4, response.getHeaders().getContentLength());
        assertEquals("4567", new String(bodyOf(response)));
    }

    @Test
    void anOpenEndedRangeRunsToTheEndOfTheFile(@TempDir Path temp) throws IOException {
        published(temp, "video.mp4", "0123456789".getBytes());

        var response = controller.download("abc123", "bytes=6-");

        assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
        assertEquals("6789", new String(bodyOf(response)));
    }

    @Test
    void aRangeThatCannotBeReadIsRefusedWithTheSize(@TempDir Path temp) throws IOException {
        published(temp, "video.mp4", "0123456789".getBytes());

        var response = controller.download("abc123", "bytes=nonsense");

        assertEquals(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, response.getStatusCode());
        assertEquals("bytes */10", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE),
                "a refusal has to say how long the file actually is");
    }

    @Test
    void theFileIsOfferedInlineUnderItsOwnName(@TempDir Path temp) throws IOException {
        published(temp, "Me at the zoo (1080p).mp4", "x".getBytes());

        var response = controller.download("abc123", null);

        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(disposition.startsWith("inline"),
                "a video should play rather than land in the downloads folder: " + disposition);
        assertTrue(disposition.contains("Me at the zoo"), disposition);
    }

    @Test
    void aLinkThatNeverExistedSaysSoInsteadOfFailing() throws IOException {
        when(shortLinks.findActiveByCode("nothinghere")).thenReturn(Optional.empty());

        var response = controller.download("nothinghere", null);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertTrue(new String(bodyOf(response)).contains("expired"),
                "whoever clicked deserves a sentence, not a blank page");
    }

    @Test
    void anExpiredLinkStopsAnswering() throws IOException {
        // Expiry is the service's decision, not the controller's: the row says so, and this is what
        // reads it.
        when(shortLinks.findActiveByCode("abc123")).thenReturn(Optional.empty());

        var response = controller.download("abc123", null);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void aFileWhoseTypeIsNotKnownIsStillServedAsVideo(@TempDir Path temp) throws IOException {
        published(temp, "clip.unknownext", "x".getBytes());

        var response = controller.download("abc123", null);

        // Everything this bot publishes is a video, and an unknown type served as octet-stream is a
        // file that downloads instead of playing.
        assertEquals("video/mp4", response.getHeaders().getContentType().toString());
    }
}
