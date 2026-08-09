package su.grinev.mediabot.media;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import su.grinev.mediabot.AgentProperties;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * The one place a URL is allowed to become an argument to a subprocess.
 *
 * <p>Two untrusted sources meet here. The link arrives in a chat message from whoever is talking to
 * the bot, and the tool call carrying it arrives from a language model that has just read a video
 * title written by a third party. Neither is a reason to fetch an arbitrary address from inside the
 * network the bot runs on.
 *
 * <p>An allowlist rather than a denylist, and hosts rather than patterns: the question "is this a
 * site we download from" has a short, knowable answer, while "is this URL dangerous" does not.
 */
@Component
@Slf4j
public class UrlGuard {

    private final List<String> allowedHosts;

    public UrlGuard(AgentProperties props) {
        this.allowedHosts = props.media().allowedHosts().stream()
                .map(host -> host.toLowerCase(Locale.ROOT))
                .toList();
    }

    /** A URL that must not be fetched, worded so the reason can go straight to the model. */
    public static class Rejected extends Exception {
        public Rejected(String message) {
            super(message);
        }
    }

    /**
     * @param raw the link as it arrived
     * @return the same link, once it is known to be safe to hand to yt-dlp
     * @throws Rejected naming what is wrong with it
     */
    public String check(String raw) throws Rejected {
        if (raw == null || raw.isBlank()) {
            throw new Rejected("no URL was given");
        }
        URI uri;
        try {
            uri = URI.create(raw.strip());
        } catch (IllegalArgumentException e) {
            throw new Rejected("'" + raw + "' is not a URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new Rejected("only http and https links can be downloaded, got '" + scheme + "'");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new Rejected("'" + raw + "' has no host in it");
        }
        host = host.toLowerCase(Locale.ROOT);

        if (!isAllowed(host)) {
            throw new Rejected(("%s is not a site this bot downloads from. It handles: %s.")
                    .formatted(host, String.join(", ", allowedHosts)));
        }
        // Checked even for an allowlisted host: a name in the allowlist that resolves to a private
        // address is either a misconfigured resolver or somebody pointing it at the local network,
        // and neither is something to fetch from.
        requirePublicAddress(host);
        return uri.toString();
    }

    /** The host itself, or a subdomain of one — {@code www.youtube.com} matches {@code youtube.com}. */
    private boolean isAllowed(String host) {
        for (String allowed : allowedHosts) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    private void requirePublicAddress(String host) throws Rejected {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new Rejected(host + " does not resolve");
        }
        for (InetAddress address : addresses) {
            if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress() || address.isAnyLocalAddress()
                    || address.isMulticastAddress()) {
                log.warn("refusing {}: it resolves to the non-public address {}",
                        host, address.getHostAddress());
                throw new Rejected(host + " resolves to an address inside this network, "
                        + "which the bot will not fetch from");
            }
        }
    }
}
