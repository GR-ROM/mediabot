package su.grinev.mediabot.jobs;

/** What a queued job does. */
public enum JobKind {

    /** Fetch video with audio, at or below a requested height. */
    DOWNLOAD,

    /** Fetch the audio track alone. */
    AUDIO,

    /**
     * Re-encode something already downloaded to a smaller height.
     *
     * <p>Rare, and worth keeping rare: a host that publishes several heights is better served by
     * asking it for the right one, which costs no CPU and loses no quality. This is for the hosts
     * that publish exactly one stream — Instagram, chiefly — where the only way down is ffmpeg.
     */
    TRANSCODE
}
