package su.grinev.mediabot.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import su.grinev.mediabot.graph.PipelineText;
import su.grinev.mediabot.jobs.JobKind;
import su.grinev.mediabot.jobs.JobSpec;
import su.grinev.mediabot.route.Request;
import su.grinev.mediabot.route.RequestRouter;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * When the model is asked, and when its answer is taken.
 *
 * <p>The direction is the point: every one of these either grows a download into a longer pipeline
 * or leaves the reading exactly as the grammar made it. None of them can end in no download at all,
 * because that is the failure the default was there to prevent.
 */
class PipelineUpgradeTest {

    private static final long CHAT = 1;
    private static final String URL = "https://www.youtube.com/watch?v=jNQXAC9IVRw";

    private final RequestRouter router = new RequestRouter();
    private IntentParser parser;
    private PipelineUpgrade upgrade;

    @BeforeEach
    void setUp() {
        parser = mock(IntentParser.class);
        upgrade = new PipelineUpgrade(router, parser);
    }

    @Test
    void wordsTheGrammarCouldNotUseBuyASecondOpinion() {
        modelSays("/download " + URL + " /normalize");

        JobSpec spec = upgraded(URL + " even out the volume");

        assertEquals(3, spec.graph().nodes().size(), "fetch, normalize, publish");
        assertEquals(URL, spec.url());
    }

    @Test
    void aBareLinkNeverReachesTheModel() {
        Request read = read(URL);

        assertSame(read, upgrade.applyTo(CHAT, URL, read));
        verify(parser, never()).parse(anyLong(), any());
    }

    @Test
    void aLinkWithOnlyAHeightIsFullyRead() {
        String text = URL + " 720p";
        Request read = read(text);

        assertSame(read, upgrade.applyTo(CHAT, text, read));
        verify(parser, never()).parse(anyLong(), any());
    }

    @Test
    void aNamedCommandIsNotSecondGuessed() {
        String text = "/audio mp3 " + URL;
        Request read = read(text);

        assertSame(read, upgrade.applyTo(CHAT, text, read));
        verify(parser, never()).parse(anyLong(), any());
    }

    @Test
    void aReadingThatIsAlreadySpecificIsLeftAlone() {
        String text = "only the audio from " + URL + " please";
        Request read = read(text);

        assertSame(read, upgrade.applyTo(CHAT, text, read));
        verify(parser, never()).parse(anyLong(), any());
    }

    @Test
    void anAnswerThatIsNoRicherKeepsTheDownload() {
        modelSays("/download " + URL);
        String text = "watch this " + URL;
        Request read = read(text);

        assertSame(read, upgrade.applyTo(CHAT, text, read),
                "the model adding nothing must not cost somebody their download");
    }

    @Test
    void aModelThatCannotAnswerKeepsTheDownload() {
        when(parser.parse(anyLong(), any())).thenReturn(Optional.empty());
        String text = "watch this " + URL;
        Request read = read(text);

        assertSame(read, upgrade.applyTo(CHAT, text, read));
    }

    @Test
    void aHeightTheUserTypedSurvivesAModelThatForgotIt() {
        modelSays("/download " + URL + " /normalize");

        JobSpec spec = upgraded(URL + " 480p and even out the volume");

        assertEquals(480, spec.maxHeight(), "the person typed it, the model merely left it out");
    }

    @Test
    void aHeightTheModelChoseIsNotOverwritten() {
        modelSays("/download " + URL + " /encode 720p /normalize");

        JobSpec spec = upgraded(URL + " 480p and even the loudness out");

        assertEquals(720, spec.maxHeight(), "a model that named a height read the same sentence");
        assertEquals(JobKind.TRANSCODE, spec.scenario());
    }

    @Test
    void aReadingTheGrammarMadeSpecificItselfIsNotSecondGuessed() {
        // "smaller" is a transcode phrase, so this never was a plain download to grow.
        String text = URL + " make it smaller";
        Request read = read(text);

        assertSame(read, upgrade.applyTo(CHAT, text, read));
        verify(parser, never()).parse(anyLong(), any());
    }

    private void modelSays(String pipeline) {
        when(parser.parse(anyLong(), any()))
                .thenReturn(Optional.of(JobSpec.of(CHAT, PipelineText.read(pipeline))));
    }

    private Request read(String text) {
        return router.parse(CHAT, text).orElseThrow();
    }

    private JobSpec upgraded(String text) {
        Request grown = upgrade.applyTo(CHAT, text, read(text));
        return ((Request.Fetch) grown).spec();
    }
}
