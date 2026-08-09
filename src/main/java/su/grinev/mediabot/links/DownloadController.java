package su.grinev.mediabot.links;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

/**
 * The other half of a link: what happens when somebody clicks it.
 *
 * <p>Streamed rather than read into memory, and served with byte ranges, because these are videos.
 * A player that cannot ask for the middle of a file has to download all of it before it can show
 * the middle, which for a two-gigabyte download is the difference between seeking and waiting.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class DownloadController {
    private static final int BUFFER = 64 * 1024;
    private final ShortLinkService shortLinks;

    @GetMapping("/d/{code}")
    public ResponseEntity<StreamingResponseBody> download(
            @PathVariable String code,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader)
            throws IOException {

        Optional<ShortLink> found = shortLinks.findActiveByCode(code);
        if (found.isEmpty()) {
            return text(HttpStatus.NOT_FOUND, "This link has expired or never existed.");
        }
        ShortLink link = found.get();
        long total = Files.size(link.file());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType(link));
        headers.setContentDisposition(ContentDisposition.inline()
                .filename(link.fileName(), StandardCharsets.UTF_8)
                .build());
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");

        if (rangeHeader == null || rangeHeader.isBlank()) {
            headers.setContentLength(total);
            return new ResponseEntity<>(out -> copy(link, 0, total, out), headers, HttpStatus.OK);
        }

        // One range only. It is what a player sends when somebody drags the scrubber, and a
        // multipart answer would be a different body entirely for nobody who asks.
        HttpRange range;
        try {
            List<HttpRange> ranges = HttpRange.parseRanges(rangeHeader);
            range = ranges.isEmpty() ? null : ranges.getFirst();
        } catch (IllegalArgumentException e) {
            range = null;
        }
        if (range == null) {
            headers.set(HttpHeaders.CONTENT_RANGE, "bytes */" + total);
            return text(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "Unreadable range.");
        }

        long start = range.getRangeStart(total);
        long end = range.getRangeEnd(total);
        long length = end - start + 1;
        headers.set(HttpHeaders.CONTENT_RANGE, "bytes %d-%d/%d".formatted(start, end, total));
        headers.setContentLength(length);
        long from = start;
        return new ResponseEntity<>(out -> copy(link, from, length, out), headers,
                HttpStatus.PARTIAL_CONTENT);
    }

    private static void copy(ShortLink link, long from, long length, OutputStream out)
            throws IOException {

        try (InputStream in = Files.newInputStream(link.file())) {
            in.skipNBytes(from);
            byte[] buffer = new byte[BUFFER];
            long left = length;
            while (left > 0) {
                int read = in.read(buffer, 0, (int) Math.min(buffer.length, left));
                if (read < 0) {
                    break;
                }
                out.write(buffer, 0, read);
                left -= read;
            }
            out.flush();
        }
    }

    private static ResponseEntity<StreamingResponseBody> text(HttpStatus status, String message) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        headers.setContentLength(body.length);
        return new ResponseEntity<>(out -> out.write(body), headers, status);
    }

    private static MediaType contentType(ShortLink link) {
        try {
            String probed = Files.probeContentType(link.file());
            if (probed != null) {
                return MediaType.parseMediaType(probed);
            }
        } catch (IOException | IllegalArgumentException e) {
            log.debug("could not tell the type of {}: {}", link.fileName(), e.toString());
        }
        return MediaType.parseMediaType("video/mp4");
    }
}
