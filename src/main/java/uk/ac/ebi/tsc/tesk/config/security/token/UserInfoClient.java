package uk.ac.ebi.tsc.tesk.config.security.token;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jose4j.json.JsonUtil;
import org.jose4j.lang.JoseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * Looks up the claims of a caller at the user info endpoint of its issuer (OpenID Connect Core 1.0,
 * section 5.3), with the caller's access token: for claims that the token lacks, e.g. the
 * entitlements, which LS Login puts only there. Asked only when a task is created, never for
 * authentication, and without a cache, so that a revoked entitlement takes effect at the next
 * create.
 *
 * <p>The endpoint is {@code tesk.api.auth.issuers[i].userinfo-uri}, else the {@code
 * userinfo_endpoint} of the issuer's discovery document (fetched once it is needed, and kept until
 * a lookup there gets no answer, or a status other than 200, 401 and 403). One attempt per lookup;
 * each request (the discovery, if needed, and the lookup) is bounded by {@code
 * tesk.api.auth.timeout-seconds} as a whole. Never logs the token or the claims.
 */
public class UserInfoClient {

    private static final Logger logger = LoggerFactory.getLogger(UserInfoClient.class);

    /** An issuer's endpoint: configured, or discovered (then kept while it answers) */
    private static final class Endpoint {
        private final String issuer;
        private final boolean configured;
        private volatile URI uri;

        Endpoint(String issuer, URI uri) {
            this.issuer = issuer;
            this.configured = uri != null;
            this.uri = uri;
        }

        /**
         * Forgets a discovered endpoint that failed, so that the next lookup discovers it again
         * (e.g. after the issuer moved it); a configured one is kept. A lookup that fails while
         * another one discovers again costs at most one more discovery.
         */
        void forgetDiscovered() {
            if (!this.configured) {
                this.uri = null;
            }
        }
    }

    private final Map<String, Endpoint> endpoints;
    private final Duration timeout;
    private final HttpClient client;

    /**
     * @param properties the settings of the issuers (checked by the token validator before)
     */
    public UserInfoClient(TokenValidationProperties properties) {
        Map<String, Endpoint> byIssuer = new LinkedHashMap<>();
        for (TokenValidationProperties.Issuer issuer : properties.getIssuers()) {
            if (!StringUtils.hasText(issuer.getUrl())) {
                continue;
            }
            String url = issuer.getUrl().strip();
            String configured = issuer.getUserinfoUri();
            byIssuer.put(
                    url,
                    new Endpoint(
                            url,
                            StringUtils.hasText(configured)
                                    ? URI.create(configured.strip())
                                    : null));
        }
        this.endpoints = Collections.unmodifiableMap(byIssuer);
        this.timeout = Duration.ofSeconds(properties.getTimeoutSeconds());
        this.client = BoundedHttp.client(this.timeout);
    }

    /**
     * The claims of the caller at its issuer's user info endpoint
     *
     * @param principal the caller, whose token was validated
     * @param token the caller's access token
     * @return the claims (a JSON object), whose {@code sub} is the caller's
     * @throws InvalidTokenException if the endpoint refuses the token (401 or 403), or answers for
     *     another subject ({@code 401})
     * @throws IssuerUnavailableException if the claims can't be looked up now: no endpoint found, a
     *     timeout, a connection error, another status, or an answer that isn't a JSON object
     *     ({@code 503})
     */
    public Map<String, Object> claims(TokenPrincipal principal, String token) {
        Endpoint endpoint = this.endpoints.get(principal.issuer());
        if (endpoint == null) {
            // not expected: the validator accepts only tokens of these issuers
            throw unavailable(principal.issuer(), "unknown issuer");
        }
        URI uri = endpoint(endpoint);
        HttpRequest request =
                HttpRequest.newBuilder(uri)
                        .header("Accept", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();
        HttpResponse<String> response;
        try {
            response = send(endpoint.issuer, request);
        } catch (IssuerUnavailableException e) {
            endpoint.forgetDiscovered();
            throw e;
        }
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            logger.info(
                    "User info endpoint of issuer {} refused the token ({})",
                    endpoint.issuer,
                    status);
            throw new InvalidTokenException("refused by the user info endpoint");
        }
        if (status != 200) {
            endpoint.forgetDiscovered();
            throw unavailable(endpoint.issuer, "status " + status);
        }
        Map<String, Object> claims = object(endpoint.issuer, response.body());
        if (!principal.subject().equals(claims.get("sub"))) {
            logger.info(
                    "User info endpoint of issuer {} answered for another sub", endpoint.issuer);
            throw new InvalidTokenException("user info of another subject");
        }
        return Collections.unmodifiableMap(claims);
    }

    /** The endpoint's URI: configured, or from the discovery (kept once found) */
    private URI endpoint(Endpoint endpoint) {
        URI uri = endpoint.uri;
        if (uri != null) {
            return uri;
        }
        String discovery = endpoint.issuer.replaceAll("/+$", "") + IssuerVerifier.DISCOVERY_PATH;
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(discovery))
                        .header("Accept", "application/json")
                        .GET()
                        .build();
        HttpResponse<String> response = send(endpoint.issuer, request);
        if (response.statusCode() != 200) {
            throw unavailable(endpoint.issuer, "discovery: status " + response.statusCode());
        }
        Map<String, Object> document = object(endpoint.issuer, response.body());
        Object found = document.get("userinfo_endpoint");
        if (!endpoint.issuer.equals(document.get("issuer"))
                || !(found instanceof String)
                || !SecureUrls.isSecure((String) found)) {
            throw unavailable(
                    endpoint.issuer,
                    "discovery document of another issuer, or without an https"
                            + " userinfo_endpoint");
        }
        uri = URI.create((String) found);
        endpoint.uri = uri;
        return uri;
    }

    /** Sends the request, bounded by the timeout as a whole */
    private HttpResponse<String> send(String issuer, HttpRequest request) {
        try {
            return BoundedHttp.send(this.client, request, this.timeout);
        } catch (BoundedHttp.Failure e) {
            throw unavailable(issuer, e.getMessage());
        }
    }

    /** The body as a JSON object */
    private static Map<String, Object> object(String issuer, String body) {
        try {
            return new LinkedHashMap<>(JsonUtil.parseJson(body));
        } catch (JoseException | RuntimeException e) {
            // e.g. a signed user info (application/jwt), or a JSON array
            throw unavailable(issuer, "answer not a JSON object");
        }
    }

    private static IssuerUnavailableException unavailable(String issuer, String reason) {
        logger.warn("User info of issuer {} unavailable: {}", issuer, reason);
        return new IssuerUnavailableException("user info unavailable: " + reason);
    }
}
