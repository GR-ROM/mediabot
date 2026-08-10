package su.grinev.mediabot.media;

/**
 * The proof-of-origin provider said something is wrong, on a run that may otherwise have worked.
 *
 * <p>An event rather than a call, for the same reason {@code JobFinished} is one: the chat side
 * already depends on the media side, so the media side cannot depend back on it. What happens with
 * this — a line in the log, a message to the owner, nothing at all — is decided by whoever is
 * listening.
 *
 * @param detail the plugin's own words, which name the address it could not reach
 */
public record ProviderUnreachable(String detail) {
}
