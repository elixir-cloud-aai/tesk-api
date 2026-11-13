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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

/**
 * Validates access tokens at the user info endpoint of the OpenID Connect provider ({@code
 * security.oauth2.resource.user-info-uri}), on each request, as Spring Security OAuth's {@code
 * UserInfoTokenServices} did before: a token is valid if the endpoint answers it with a JSON object
 * that has a {@code sub} and no {@code error}. The user ({@link ElixirPrincipalExtractor}) and its
 * authorities, the group names ({@link GroupNamesSecurityExtractor}), are taken from that answer.
 *
 * <p>Logs why a token was refused, never the token.
 */
public class UserInfoTokenIntrospector implements OpaqueTokenIntrospector {

    private static final Logger logger = LoggerFactory.getLogger(UserInfoTokenIntrospector.class);

    /** Description of the error for all tokens that the user info endpoint doesn't accept */
    static final String INVALID_TOKEN = "Invalid access token";

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
     * @throws IllegalStateException if the user info endpoint isn't set
     * @throws IllegalArgumentException if it isn't a URI
     */
    public UserInfoTokenIntrospector(
            String userInfoUri,
            ElixirPrincipalExtractor principalExtractor,
            GroupNamesSecurityExtractor authoritiesExtractor) {
        this(userInfoUri, principalExtractor, authoritiesExtractor, new RestTemplate());
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
        } catch (RuntimeException e) {
            // the message of a client error can hold the answer, but never the token
            logger.warn("Could not fetch user details: {}, {}", e.getClass(), e.getMessage());
            throw new BadOpaqueTokenException(INVALID_TOKEN);
        }
        if (body == null) {
            logger.warn("Refused token: the user info is empty");
            throw new BadOpaqueTokenException(INVALID_TOKEN);
        }
        return new LinkedHashMap<>(body);
    }
}
