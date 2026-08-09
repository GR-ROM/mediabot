package su.grinev.mediabot.jobs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The job as one complete value.
 *
 * <p>What is being pinned down is that nothing downstream has to fill a blank in. A worker that
 * defaults a missing height, or a store that decides what an absent audio format means, is a second
 * place where the shape of the work is chosen — and two places disagree eventually.
 */
class JobSpecTest {

    @Test
    void anAudioJobCarriesAContainerEvenWhenNobodyAskedForOne() {
        JobSpec spec = JobSpec.audio(1, "https://example.test/v", null);

        assertEquals("m4a", spec.audioFormat(), "the worker must not have to invent this");
        assertNull(spec.maxHeight(), "a height on an audio job is a field that means nothing");
    }

    @Test
    void aTranscodeAlwaysKnowsWhatHeightItIsEncodingTo() {
        assertEquals(720, JobSpec.transcode(1, "https://example.test/v", null).maxHeight());
        assertEquals(480, JobSpec.transcode(1, "https://example.test/v", 480).maxHeight());
    }

    @Test
    void aDownloadWithNoCeilingStaysWithoutOne() {
        JobSpec spec = JobSpec.download(1, "https://example.test/v", null);

        assertNull(spec.maxHeight(), "best available is a request, not a missing value");
        assertNull(spec.audioFormat());
    }

    @Test
    void aFormatIsNormalisedRatherThanPassedOnAsTyped() {
        assertEquals("mp3", JobSpec.audio(1, "https://example.test/v", "MP3").audioFormat());
    }

    @Test
    void aSpecWithoutTheOneThingItNeedsIsNotBuildable() {
        assertThrows(IllegalArgumentException.class,
                () -> JobSpec.download(1, "  ", 720));
        assertThrows(IllegalArgumentException.class,
                () -> new JobSpec(1, null, "https://example.test/v", null, null, null));
    }

    @Test
    void whatWasAskedForIsSaidInWordsForEachScenario() {
        assertEquals("best quality", JobSpec.download(1, "u", null).describe());
        assertEquals("up to 720p", JobSpec.download(1, "u", 720).describe());
        assertEquals("re-encoded to 480p", JobSpec.transcode(1, "u", 480).describe());
        assertEquals("audio only, as mp3", JobSpec.audio(1, "u", "mp3").describe());
    }
}
