package uk.ac.ebi.tsc.tesk.authz;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes credentials, query and fragment from the URLs of a task's inputs and outputs before they
 * are sent to the decision service (profile {@code tes-authzen/1}, URL stripping), with a plain
 * string algorithm: URL parsers of different languages disagree on schemes such as {@code s3://} or
 * {@code drs://}, IPv6 hosts and malformed input, and falling back to the raw value would leak the
 * very secrets that are removed (userinfo, presigned signatures, SAS tokens).
 *
 * <ol>
 *   <li>(Step 0) A value with a tab, line feed or carriage return anywhere is left out: WHATWG
 *       parsers delete them, so {@code https://<TAB>/u:p@h/p} would send {@code u:p} to {@code h}.
 *   <li>A scheme counts only if the value starts with a valid RFC 3986 scheme directly followed by
 *       {@code ://}; the part W runs from there to the first {@code /} (or the end).
 *   <li>If W has an {@code @} after its first {@code ?} or {@code #}, or a backslash (which WHATWG
 *       parsers read as {@code /}, ending the host there), the value is ambiguous and is left out.
 *   <li>The authority is W up to its first {@code ?} or {@code #}; everything up to and including
 *       its last {@code @} is dropped.
 *   <li>The rest is cut at its first {@code ?} or {@code #}; an {@code @} in the path is kept,
 *       unless the authority left is empty (step 4b): then the value is left out, as WHATWG parsers
 *       skip the extra slashes, so {@code https:///u:p@h/p} or {@code https://x@/u:p@h/p} sends
 *       {@code u:p} to {@code h}.
 *   <li>A value without a scheme is kept only if it has no {@code @} and no {@code ?}.
 *   <li>Nothing else changes (case, percent-encoding, ports).
 * </ol>
 */
public final class UrlStripper {

    /** RFC 3986: {@code ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )}, then {@code ://} */
    private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://");

    private UrlStripper() {}

    /**
     * The URL without credentials, query and fragment, or null if it must be left out
     *
     * @param url the URL as the client sent it
     */
    public static String strip(String url) {
        if (url == null
                || url.indexOf('\t') >= 0
                || url.indexOf('\n') >= 0
                || url.indexOf('\r') >= 0) {
            return null;
        }
        Matcher scheme = SCHEME.matcher(url);
        if (!scheme.find()) {
            return url.indexOf('@') < 0 && url.indexOf('?') < 0 ? url : null;
        }
        int start = scheme.end();
        int slash = url.indexOf('/', start);
        int end = slash < 0 ? url.length() : slash;
        String w = url.substring(start, end);
        String rest = url.substring(end);
        int queryOrFragment = firstQueryOrFragment(w);
        if (w.indexOf('\\') >= 0 || queryOrFragment >= 0 && w.lastIndexOf('@') > queryOrFragment) {
            return null;
        }
        String authority = queryOrFragment < 0 ? w : w.substring(0, queryOrFragment);
        if (queryOrFragment >= 0) {
            rest = w.substring(queryOrFragment) + rest;
        }
        authority = authority.substring(authority.lastIndexOf('@') + 1);
        int cut = firstQueryOrFragment(rest);
        if (cut >= 0) {
            rest = rest.substring(0, cut);
        }
        if (authority.isEmpty() && rest.indexOf('@') >= 0) {
            return null;
        }
        return url.substring(0, start) + authority + rest;
    }

    /** The index of the first {@code ?} or {@code #}, or -1 */
    private static int firstQueryOrFragment(String value) {
        int query = value.indexOf('?');
        int fragment = value.indexOf('#');
        if (query < 0) {
            return fragment;
        }
        return fragment < 0 ? query : Math.min(query, fragment);
    }
}
