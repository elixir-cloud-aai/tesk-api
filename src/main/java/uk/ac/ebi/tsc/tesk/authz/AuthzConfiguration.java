package uk.ac.ebi.tsc.tesk.authz;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import uk.ac.ebi.tsc.tesk.tes.service.TaskAccess;
import uk.ac.ebi.tsc.tesk.tes.service.TaskAccess.Operation;

/**
 * With authentication (the auth profile), the decision service that decides who may create tasks
 * ({@link CreateAuthorisation}, the bean {@code createAuthorisation}). Its settings ({@code
 * tesk.api.authz.*}, {@link AuthzProperties}) are checked at startup: TESK API refuses to start
 * without a decision service, with one that isn't https (except on the local host), with a timeout
 * out of range, a blank claim name, or a credential file that can't be read.
 *
 * <p>Get, list and cancel are decided by the decision service too if the operator switches them on
 * ({@code tesk.api.authz.operations}, the bean {@code taskAccess}, {@link OperationAuthorisation}),
 * each failing closed or open ({@code tesk.api.authz.failure.<operation>}). TESK API refuses to
 * start if the operations don't include {@code create}, name an unknown operation or one twice, if
 * a failure setting names an unknown operation or has a value other than {@code closed} or {@code
 * open}, or if create is set to fail open.
 */
@Configuration
@Profile("auth")
@EnableConfigurationProperties(AuthzProperties.class)
public class AuthzConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(AuthzConfiguration.class);

    private static final String PREFIX = "tesk.api.authz.decision-service.";

    private static final String OPERATIONS = "tesk.api.authz.operations";

    private static final String FAILURE = "tesk.api.authz.failure.";

    private static final String KNOWN = " (known: create, get, list, cancel)";

    private static final String NO_CREATE_RULE =
            ": TESK API has no rule of its own for who may create tasks";

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
     * Who may get, list and cancel which tasks: the decision service for the operations that are
     * switched on, the owner rule for the others
     *
     * @throws IllegalStateException if the operations or failure settings are invalid ({@link
     *     #operations}, {@link #failOpen})
     */
    @Bean
    public TaskAccess taskAccess(
            DecisionServiceClient decisionServiceClient,
            AuthzProperties properties,
            ServiceInfoService serviceInfoService) {
        Set<Operation> operations = operations(properties.getOperations());
        Set<Operation> failOpen = failOpen(properties.getFailure(), operations);
        return new OperationAuthorisation(
                decisionServiceClient,
                new EvaluationRequests(
                        claims(properties.getDecisionService()),
                        () -> serviceInfoService.getServiceInfo().getId()),
                operations,
                failOpen);
    }

    /**
     * The operations that the decision service decides
     *
     * @throws IllegalStateException if they don't include {@code create}, or name an unknown
     *     operation or one twice
     */
    static Set<Operation> operations(List<String> names) {
        Set<Operation> operations = EnumSet.noneOf(Operation.class);
        for (String name : names) {
            String stripped = name == null ? "" : name.strip();
            Optional<Operation> operation = Operation.ofSetting(stripped);
            if (operation.isEmpty()) {
                throw new IllegalStateException(
                        OPERATIONS + " has an unknown operation '" + stripped + "'" + KNOWN);
            }
            if (!operations.add(operation.get())) {
                throw new IllegalStateException(
                        OPERATIONS + " names '" + stripped + "' more than once");
            }
        }
        if (!operations.contains(Operation.CREATE)) {
            throw new IllegalStateException(OPERATIONS + " must include create" + NO_CREATE_RULE);
        }
        return operations;
    }

    /**
     * The operations that fall back to the owner rule if a decision fails ({@code open}); a warning
     * for one that isn't switched on, where the setting changes nothing
     *
     * @throws IllegalStateException if a setting names an unknown operation, has a value other than
     *     {@code closed} or {@code open}, or sets create to {@code open}
     */
    static Set<Operation> failOpen(Map<String, String> failure, Set<Operation> operations) {
        Set<Operation> failOpen = EnumSet.noneOf(Operation.class);
        for (Map.Entry<String, String> setting : failure.entrySet()) {
            String key = setting.getKey();
            Optional<Operation> operation = Operation.ofSetting(key);
            if (operation.isEmpty()) {
                throw new IllegalStateException(
                        FAILURE + key + " names an unknown operation" + KNOWN);
            }
            String value = setting.getValue() == null ? "" : setting.getValue().strip();
            if (!value.equals("closed") && !value.equals("open")) {
                throw new IllegalStateException(FAILURE + key + " must be closed or open");
            }
            if (value.equals("open")) {
                if (operation.get() == Operation.CREATE) {
                    throw new IllegalStateException(
                            FAILURE + "create can't be open" + NO_CREATE_RULE);
                }
                if (!operations.contains(operation.get())) {
                    logger.warn(
                            "{}{} is open, but {} doesn't include {}: the setting changes nothing",
                            FAILURE,
                            key,
                            OPERATIONS,
                            key);
                }
                failOpen.add(operation.get());
            }
        }
        return failOpen;
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
                                + " printable ASCII without spaces");
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
