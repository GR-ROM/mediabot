package su.grinev.mediabot.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import su.grinev.mediabot.graph.Node;
import su.grinev.mediabot.jobs.JobKind;
import su.grinev.mediabot.jobs.JobSpec;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the model is allowed to turn into a job, now that it answers in the same language the slash
 * form is written in.
 */
class IntentParserTest {

    private static final long CHAT = 1;
    private static final String URL = "https://www.youtube.com/watch?v=jNQXAC9IVRw";

    private LlmClient llm;
    private IntentParser parser;

    @BeforeEach
    void setUp() {
        llm = mock(LlmClient.class);
        LlmAvailability availability = mock(LlmAvailability.class);
        when(availability.shouldTry()).thenReturn(true);
        parser = new IntentParser(llm, availability, new ObjectMapper());
    }

    @Test
    void aPipelineComesBackAsAJob() throws Exception {
        answers("/download <url> /cut 1:00-1:15 2:40-3:00 /encode 720p /normalize");

        JobSpec spec = parse("keep the two good bits, smaller, and even out the volume " + URL);

        assertEquals(URL, spec.url());
        assertEquals(2, spec.graph().outputs().size());
        assertEquals(720, spec.maxHeight());
        assertEquals(JobKind.TRANSCODE, spec.scenario());
    }

    @Test
    void theLinkComesFromTheMessageAndNotFromTheModel() throws Exception {
        answers("/download https://evil.test/invented /audio mp3");

        JobSpec spec = parse("just the sound of " + URL);

        assertEquals(URL, spec.url(), "an invented link is a perfectly well-formed link");
        assertEquals(URL, spec.graph().source().orElseThrow().url());
        assertEquals("mp3", spec.audioFormat());
    }

    @Test
    void aStepThatDoesNotExistIsNotAJob() throws Exception {
        answers("/download <url> /deblur /upscale 4k");

        assertTrue(parseMaybe(URL + " make it look better").isEmpty(),
                "the grammar refuses it, so the model cannot invent an operation");
    }

    @Test
    void unclearIsAnAnswerAndNotAJob() throws Exception {
        answers("unclear");

        assertTrue(parseMaybe("is this any good? " + URL).isEmpty());
    }

    @Test
    void theLineIsCutOutOfWhateverProseItArrivedIn() throws Exception {
        answers("""
                Sure! Here is the pipeline you asked for:
                ```
                /download <url> /encode 480p
                ```
                Let me know if you need anything else.""");

        JobSpec spec = parse("shrink " + URL);

        assertEquals(480, spec.maxHeight());
        assertEquals(URL, spec.url());
    }

    @Test
    void aMessageWithNoLinkNeverReachesTheModel() throws Exception {
        assertTrue(parseMaybe("what can you do for me?").isEmpty());
        org.mockito.Mockito.verify(llm, org.mockito.Mockito.never()).complete(any(), any());
    }

    @Test
    void theHeightIsTakenFromTheStepTheModelWrote() throws Exception {
        answers("/download <url> 1080p");

        JobSpec spec = parse("best you can do " + URL);

        assertEquals(JobKind.DOWNLOAD, spec.scenario());
        assertEquals(1080, spec.graph().nodes().stream()
                .filter(Node.Fetch.class::isInstance).map(Node.Fetch.class::cast)
                .findFirst().orElseThrow().maxHeight());
    }

    private void answers(String content) throws Exception {
        when(llm.complete(any(), any()))
                .thenReturn(new LlmClient.Completion(content, List.of(), null, null));
    }

    private JobSpec parse(String text) {
        return parser.parse(CHAT, text).orElseThrow();
    }

    private Optional<JobSpec> parseMaybe(String text) {
        return parser.parse(CHAT, text);
    }
}
