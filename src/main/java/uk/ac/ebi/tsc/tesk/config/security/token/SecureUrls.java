package uk.ac.ebi.tsc.tesk.config.security.token;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Which URLs TESK API may call for decisions about callers (an issuer's discovery, keys and user
 * info endpoint, the decision service): https, or plain http to the local host only (e.g. for
 * tests, or a decision service next to TESK API in the same pod), as anyone on the network path of
 * plain http could change the answers
 */
public final class SecureUrls {

    private SecureUrls() {}

    /**
     * @param url the URL
     * @return whether it is an https URL with a host, or an http URL to {@code localhost}, {@code
     *     127.0.0.1} or {@code [::1]}
     */
    public static boolean isSecure(String url) {
        if (url == null) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return false;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if ("https".equalsIgnoreCase(scheme)) {
            return host != null;
        }
        return "http".equalsIgnoreCase(scheme)
                && ("localhost".equalsIgnoreCase(host)
                        || "127.0.0.1".equals(host)
                        || "[::1]".equals(host));
    }
}
