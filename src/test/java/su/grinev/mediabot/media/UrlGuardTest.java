package su.grinev.mediabot.media;

import org.junit.jupiter.api.Test;
import su.grinev.mediabot.AgentProperties;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard is the boundary between two untrusted sources — a chat message and a model's tool call —
 * and a subprocess. These are the cases that must not get through it.
 */
class UrlGuardTest {

    private final UrlGuard guard = new UrlGuard(propsAllowing("youtube.com", "youtu.be",
            "instagram.com"));

    @Test
    void allowlistedHostsAndTheirSubdomainsPass() {
        assertDoesNotThrow(() -> guard.check("https://www.youtube.com/watch?v=abc"));
        assertDoesNotThrow(() -> guard.check("https://youtu.be/abc"));
        assertDoesNotThrow(() -> guard.check("https://instagram.com/reel/abc"));
    }

    @Test
    void anythingOutsideTheAllowlistIsRefused() {
        var refusal = assertThrows(UrlGuard.Rejected.class,
                () -> guard.check("https://example.com/video.mp4"));
        assertTrue(refusal.getMessage().contains("example.com"),
                "the refusal has to name the host, since the model repeats it to the user");
    }

    @Test
    void aHostThatOnlyLooksAllowlistedIsRefused() {
        // The check is on the domain boundary, not on the string: "youtube.com.evil.test" ends with
        // the allowed name only if you forget the dot.
        assertThrows(UrlGuard.Rejected.class,
                () -> guard.check("https://youtube.com.evil.test/x"));
        assertThrows(UrlGuard.Rejected.class,
                () -> guard.check("https://notyoutube.com/x"));
    }

    @Test
    void onlyHttpAndHttpsAreFetchable() {
        assertThrows(UrlGuard.Rejected.class, () -> guard.check("file:///C:/Windows/win.ini"));
        assertThrows(UrlGuard.Rejected.class, () -> guard.check("ftp://youtube.com/x"));
    }

    @Test
    void addressesInsideTheNetworkAreRefusedEvenWhenAllowlisted() {
        // localhost is on this allowlist on purpose: an allowlisted name that resolves to a private
        // address is exactly the case the second check exists for.
        UrlGuard local = new UrlGuard(propsAllowing("localhost"));
        var refusal = assertThrows(UrlGuard.Rejected.class,
                () -> local.check("http://localhost:8080/admin"));
        assertTrue(refusal.getMessage().contains("inside this network"));
    }

    @Test
    void nonsenseIsRefusedRatherThanPassedOn() {
        assertThrows(UrlGuard.Rejected.class, () -> guard.check(""));
        assertThrows(UrlGuard.Rejected.class, () -> guard.check(null));
        assertThrows(UrlGuard.Rejected.class, () -> guard.check("https://"));
    }

    private static AgentProperties propsAllowing(String... hosts) {
        return new AgentProperties(
                new AgentProperties.Llm("http://localhost:11434/v1", "", "", "m",
                        1024, 0.1, 60, false),
                new AgentProperties.Telegram("", "", "", 50L * 1024 * 1024, 0, List.of(), List.of()),
                new AgentProperties.Media(Path.of("yt-dlp"), Path.of("ffmpeg"), Path.of("work"),
                        List.of(hosts), 60, 600, null, null, null, 720, 2160),
                new AgentProperties.Links("http://localhost:8080", Path.of("build/test-public"), 24),
                new AgentProperties.Jobs(Path.of("jobs.db"), 1, 5, 24));
    }
}
