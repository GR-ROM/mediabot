package su.grinev.mediabot.media;

import java.util.function.DoubleConsumer;

/**
 * Two halves of the work, reported apart.
 *
 * <p>Pulling bytes and re-encoding them fail differently, take differently long, and mean different
 * things to somebody watching. A single progress number cannot say which one is happening, so a bar
 * that has been at 100% for two minutes reads as a stuck download when it is ffmpeg doing its job.
 */
public interface DownloadProgress {

    void downloading(double fraction);

    void processing(double fraction);

    DownloadProgress NONE = of(fraction -> { }, fraction -> { });

    static DownloadProgress of(DoubleConsumer downloading, DoubleConsumer processing) {
        return new DownloadProgress() {
            @Override
            public void downloading(double fraction) {
                downloading.accept(fraction);
            }

            @Override
            public void processing(double fraction) {
                processing.accept(fraction);
            }
        };
    }
}
