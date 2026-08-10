package su.grinev.mediabot.links;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import su.grinev.mediabot.AgentProperties;
import su.grinev.mediabot.telegram.MediaBot;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A streamed response has to outlive the link it is streaming.
 *
 * <p>Left at its default, the async timeout cut a download off after about ninety seconds, in the
 * middle of the body: the client got a truncated file, asked again, got another one, and it read as
 * a download that hung. Anything over roughly 450 MB on a phone reached it, which is most of what
 * this bot exists to hand over.
 *
 * <p>So the rule, rather than the number: however long a link keeps working, the response serving
 * it may take at least that long. Written as a test because the two values live in different
 * sections of the same file and nothing else would notice them drifting apart.
 */
@SpringBootTest(properties = {
        "mediabot.llm.preflight=false",
        "mediabot.telegram.token=",
        "mediabot.jobs.database-path=build/test-work/timeout/jobs.db",
        "mediabot.media.work-dir=build/test-work/timeout/media",
        "mediabot.links.dir=build/test-work/timeout/public"
})
class StreamingTimeoutTest {

    @MockitoBean
    private MediaBot bot;

    @Test
    void aResponseMayTakeAsLongAsTheLinkItServesLives(@Autowired Environment environment,
                                                      @Autowired AgentProperties props) {

        Long timeout = environment.getProperty("spring.mvc.async.request-timeout", Long.class);

        assertTrue(timeout != null, "unset is ninety seconds, which truncates every large file");
        Duration linkLife = Duration.ofHours(props.links().ttlHours());
        assertTrue(timeout == -1 || timeout >= linkLife.toMillis(),
                "a link lives " + linkLife + " but a response serving it is cut off after "
                        + Duration.ofMillis(timeout));
    }
}
