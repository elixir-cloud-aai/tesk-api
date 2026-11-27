package uk.ac.ebi.tsc.tesk.authz;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;
import uk.ac.ebi.tsc.tesk.config.security.token.SecureUrls;
import uk.ac.ebi.tsc.tesk.config.security.token.TokenValidationProperties;
import uk.ac.ebi.tsc.tesk.config.security.token.UserInfoClient;
import uk.ac.ebi.tsc.tesk.tes.service.ServiceInfoService;

/**
 * With authentication (the auth profile), the decision service that decides who may create tasks
 * ({@link CreateAuthorisation}, the bean {@code createAuthorisation}). Its settings ({@code
 * tesk.api.authz.*}, {@link AuthzProperties}) are checked at startup: TESK API refuses to start
 * without a decision service, with one that isn't https (except on the local host), with a timeout
 * out of range, a blank claim name, or a credential file that can't be read.
 */
@Configuration
@Profile("auth")
@EnableConfigurationProperties(AuthzProperties.class)
public class AuthzConfiguration {

    private static final String PREFIX = "tesk.api.authz.decision-service.";

    /** Upper bound of the timeout of the decision service, in seconds */
    static final int MAX_TIMEOUT_SECONDS = 60;

    /**
     * The client of the decision service, after the token validator, so that a deployment without
     * any settings learns first that it needs an issuer
     *
     * @throws IllegalStateException if the settings are missing or invalid ({@link #check})
     */
    @Bean
    @DependsOn("tokenValidator")
    public DecisionServiceClient decisionServiceClient(AuthzProperties properties) {
        AuthzProperties.DecisionService settings = properties.getDecisionService();
        String url = check(settings);
        String file = settings.getCredentialFile();
        return new DecisionServiceClient(
                URI.create(url),
                Duration.ofSeconds(settings.getTimeout()),
                StringUtils.hasText(file) ? Path.of(file.strip()) : null);
    }

    /**
     * The client of the issuers' user info endpoints, after the token validator, which checks their
     * settings
     */
    @Bean
    @DependsOn("tokenValidator")
    public UserInfoClient userInfoClient(TokenValidationProperties properties) {
        return new UserInfoClient(properties);
    }

    /** The decision whether the caller may create a task, for {@code @PreAuthorize} */
    @Bean
    public CreateAuthorisation createAuthorisation(
            DecisionServiceClient decisionServiceClient,
            UserInfoClient userInfoClient,
            AuthzProperties properties,
            ServiceInfoService serviceInfoService) {
        return new CreateAuthorisation(
                decisionServiceClient,
                userInfoClient,
                claims(properties.getDecisionService()),
                () -> serviceInfoService.getServiceInfo().getId(),
                CreateAuthorisation::currentAccessToken);
    }

    /**
     * Checks the settings of the decision service
     *
     * @return its URL, without surrounding white space
     * @throws IllegalStateException if a setting is missing or invalid, naming it
     */
    static String check(AuthzProperties.DecisionService settings) {
        String url = settings.getUrl();
        if (!StringUtils.hasText(url)) {
            throw new IllegalStateException(
                    PREFIX
                            + "url must be set: with authentication, a decision service decides"
                            + " who may create tasks (see the configuration reference)");
        }
        url = url.strip();
        if (!SecureUrls.isSecure(url)) {
            throw new IllegalStateException(
                    PREFIX + "url must be an https URL (http only for localhost)");
        }
        if (settings.getTimeout() < 1 || settings.getTimeout() > MAX_TIMEOUT_SECONDS) {
            throw new IllegalStateException(
                    PREFIX + "timeout must be between 1 and " + MAX_TIMEOUT_SECONDS);
        }
        claims(settings);
        String file = settings.getCredentialFile();
        if (StringUtils.hasText(file)) {
            try {
                DecisionServiceClient.readCredential(Path.of(file.strip()));
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException(
                        PREFIX
                                + "credential-file can't be read, is empty or isn't one line of"
                                + " printable ASCII");
            }
        }
        return url;
    }

    /**
     * The claim names, without surrounding white space
     *
     * @throws IllegalStateException if one is blank
     */
    static List<String> claims(AuthzProperties.DecisionService settings) {
        List<String> names = new ArrayList<>();
        for (String name : settings.getClaims()) {
            if (!StringUtils.hasText(name)) {
                throw new IllegalStateException(PREFIX + "claims must not have blank values");
            }
            names.add(name.strip());
        }
        return names;
    }
}
