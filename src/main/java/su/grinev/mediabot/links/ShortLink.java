package su.grinev.mediabot.links;

import java.nio.file.Path;
import java.time.Instant;

public record ShortLink(String code, long jobId, long chatId, Path file, String fileName,
                   long sizeBytes, Instant createdAt, Instant expiresAt) {

    public boolean expiredAt(Instant moment) {
        return !moment.isBefore(expiresAt);
    }
}
