package su.grinev.mediabot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import su.grinev.mediabot.jobs.JobQueue;
import su.grinev.mediabot.route.RequestRouter;
import su.grinev.mediabot.telegram.ChatDispatcher;
import su.grinev.mediabot.telegram.MediaBot;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The context builds, which is most of what an ordinary Spring application can get wrong.
 */
@SpringBootTest(properties = {
        "mediabot.llm.preflight=false",
        "mediabot.telegram.token=",
        "mediabot.jobs.database-path=build/test-work/context/jobs.db",
        "mediabot.media.work-dir=build/test-work/context/media",
        "mediabot.links.dir=build/test-work/context/public"
})
class ContextLoadsTest {

    @MockitoBean
    private MediaBot bot;

    @Autowired
    private ChatDispatcher dispatcher;

    @Autowired
    private RequestRouter router;

    @Test
    void theChatSideAndTheQueueDoNotDependOnEachOtherInACircle(@Autowired JobQueue queue) {
        // The queue publishes events and takes a ProgressSink interface; the dispatcher depends on
        // the queue. Reverse either and the context stops building.
        assertNotNull(dispatcher);
        assertNotNull(queue);
        assertNotNull(router);
    }
}
