package uk.ac.ebi.tsc.tesk.config.security;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

/**
 * Validates access tokens at the user info endpoint of the OpenID Connect provider ({@code
 * security.oauth2.resource.user-info-uri}), on each request: a token is valid if the endpoint
 * answers it with a JSON object that has a {@code sub} and no {@code error}. Any failure (an error
 * status, an unreachable endpoint, an answer that isn't such an object) refuses the token. The user
 * ({@link ElixirPrincipalExtractor}) and its authorities, the group names ({@link
 * GroupNamesSecurityExtractor}), are taken from that answer.
 *
 * <p>Logs why a token was refused, never the token or the answer of the endpoint.
 */
public class UserInfoTokenIntrospector implements OpaqueTokenIntrospector {

    private static final Logger logger = LoggerFactory.getLogger(UserInfoTokenIntrospector.class);

    /** Description of the error for all tokens that the user info endpoint doesn't accept */
    static final String INVALID_TOKEN = "Invalid access token";

    /** Timeout of connections to the user info endpoint, in milliseconds */
    static final int CONNECT_TIMEOUT_MS = 10_000;

    /** Timeout of reading the answer of the user info endpoint, in milliseconds */
    static final int READ_TIMEOUT_MS = 30_000;

    private static final ParameterizedTypeReference<Map<String, Object>> USER_INFO =
            new ParameterizedTypeReference<Map<String, Object>>() {};

    private final URI userInfoUri;
    private final ElixirPrincipalExtractor principalExtractor;
    private final GroupNamesSecurityExtractor authoritiesExtractor;
    private final RestOperations restOperations;

    /**
     * @param userInfoUri the user info endpoint
     * @param principalExtractor builds the user from the user info
     * @param authoritiesExtractor takes the authorities (group names) from the user info
     * @throws IllegalStateException if the user info endpoint isn't an absolute http(s) URL
     * @throws IllegalArgumentException if it isn't a URI
     */
    public UserInfoTokenIntrospector(
            String userInfoUri,
            ElixirPrincipalExtractor principalExtractor,
            GroupNamesSecurityExtractor authoritiesExtractor) {
        this(userInfoUri, principalExtractor, authoritiesExtractor, restTemplate());
    }

    /**
     * The client of the user info endpoint, with timeouts, so that a hanging endpoint doesn't hold
     * request threads (a timeout refuses the token)
     */
    static RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MS);
        return new RestTemplate(requestFactory);
    }

    /** As above, with the client of the user info endpoint (for tests) */
    UserInfoTokenIntrospector(
            String userInfoUri,
            ElixirPrincipalExtractor principalExtractor,
            GroupNamesSecurityExtractor authoritiesExtractor,
            RestOperations restOperations) {
        if (!StringUtils.hasText(userInfoUri)) {
            throw new IllegalStateException(
                    "The auth profile needs security.oauth2.resource.user-info-uri"
                            + " (SECURITY_OAUTH2_RESOURCE_USER_INFO_URI)");
        }
        this.userInfoUri = URI.create(userInfoUri.trim());
        String scheme = this.userInfoUri.getScheme();
        if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                || this.userInfoUri.getRawAuthority() == null) {
            throw new IllegalStateException(
                    "security.oauth2.resource.user-info-uri (SECURITY_OAUTH2_RESOURCE_USER_INFO_URI)"
                            + " must be an absolute http(s) URL");
        }
        this.principalExtractor = Objects.requireNonNull(principalExtractor);
        this.authoritiesExtractor = Objects.requireNonNull(authoritiesExtractor);
        this.restOperations = Objects.requireNonNull(restOperations);
    }

    @Override
    public OAuth2AuthenticatedPrincipal introspect(String token) {
        Map<String, Object> userInfo = userInfo(token);
        if (userInfo.containsKey("error")) {
            logger.warn("Refused token: the user info has an error");
            throw new BadOpaqueTokenException(INVALID_TOKEN);
        }
        if (userInfo.get("sub") == null) {
            logger.warn("Refused token: the user info has no sub");
            throw new BadOpaqueTokenException(INVALID_TOKEN);
        }
        User user = this.principalExtractor.extractPrincipal(userInfo);
        List<GrantedAuthority> authorities = this.authoritiesExtractor.extractAuthorities(userInfo);
        return new AuthenticatedUser(user, userInfo, authorities);
    }

    /** The user info of the token's owner; any failure means that the token isn't valid */
    private Map<String, Object> userInfo(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        RequestEntity<Void> request =
                new RequestEntity<>(headers, HttpMethod.GET, this.userInfoUri);
        Map<String, Object> body;
        try {
            body = this.restOperations.exchange(request, USER_INFO).getBody();
        } catch (RestClientResponseException e) {
            // its message holds the answer, which may have personal data: the status only
            logger.warn("Could not fetch user details: status {}", e.getRawStatusCode());
            throw new BadOpaqueTokenException(INVALID_TOKEN);
        } catch (ResourceAccessException e) {
            // e.g. connection refused; its own message has the URI, which may hold credentials
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            logger.warn(
                    "Could not fetch user details: {}: {}",
                    cause.getClass().getSimpleName(),
                    cause.getMessage());
            throw new BadOpaqueTokenException(INVALID_TOKEN);
        } catch (RuntimeException e) {
            // e.g. an answer that isn't a JSON object: its message may quote the answer
            logger.warn("Could not read user details: {}", e.getClass().getName());
            throw new BadOpaqueTokenException(INVALID_TOKEN);
        }
        if (body == null) {
            logger.warn("Refused token: the user info is empty");
            throw new BadOpaqueTokenException(INVALID_TOKEN);
        }
        return new LinkedHashMap<>(body);
    }
}
