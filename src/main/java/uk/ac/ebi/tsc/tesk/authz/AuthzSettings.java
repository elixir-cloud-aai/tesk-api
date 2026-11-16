package uk.ac.ebi.tsc.tesk.authz;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The settings {@code tesk.api.authz.*}: the required claim ({@code required-claim}), the decision
 * service ({@code decision-service}) and the issuer of the users' tokens ({@code issuer}). All are
 * unset by default.
 *
 * <p>List-valued settings ({@code required-claim.values}, {@code required-claim.prefixes} and
 * {@code decision-service.claims}) are lists in configuration files, and JSON arrays in environment
 * variables (e.g. {@code TESK_API_AUTHZ_REQUIRED_CLAIM_PREFIXES='["urn:example:vo:"]'}), as group
 * names can contain commas. A single value that isn't a JSON array is split at commas, as Spring
 * does for other lists.
 */
public final class AuthzSettings {

    static final String PREFIX = "tesk.api.authz.";
    static final String ISSUER = PREFIX + "issuer";
    static final String CLAIM_NAME = PREFIX + "required-claim.name";
    static final String CLAIM_VALUES = PREFIX + "required-claim.values";
    static final String CLAIM_PREFIXES = PREFIX + "required-claim.prefixes";
    static final String URL = PREFIX + "decision-service.url";
    static final String TIMEOUT = PREFIX + "decision-service.timeout";
    static final String CLAIMS = PREFIX + "decision-service.claims";
    static final String CREDENTIAL_FILE = PREFIX + "decision-service.credential-file";

    /** Default timeout of a call to the decision service, in seconds */
    static final double DEFAULT_TIMEOUT = 2;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String issuer;
    private final String claimName;
    private final List<String> claimValues;
    private final List<String> claimPrefixes;
    private final String url;
    private final String timeout;
    private final List<String> claims;
    private final String credentialFile;

    AuthzSettings(
            String issuer,
            String claimName,
            List<String> claimValues,
            List<String> claimPrefixes,
            String url,
            String timeout,
            List<String> claims,
            String credentialFile) {
        this.issuer = blankToNull(issuer);
        this.claimName = blankToNull(claimName);
        this.claimValues = unmodifiable(claimValues);
        this.claimPrefixes = unmodifiable(claimPrefixes);
        this.url = blankToNull(url);
        this.timeout = blankToNull(timeout);
        this.claims = unmodifiable(claims);
        this.credentialFile = blankToNull(credentialFile);
    }

    /** The settings of the environment */
    public static AuthzSettings from(Environment environment) {
        return new AuthzSettings(
                environment.getProperty(ISSUER),
                environment.getProperty(CLAIM_NAME),
                list(environment, CLAIM_VALUES),
                list(environment, CLAIM_PREFIXES),
                environment.getProperty(URL),
                environment.getProperty(TIMEOUT),
                list(environment, CLAIMS),
                environment.getProperty(CREDENTIAL_FILE));
    }

    /**
     * A list setting: a single value (e.g. an environment variable) as a JSON array of strings, or
     * split at commas; else the list of a configuration file
     *
     * @throws IllegalStateException if a JSON array is malformed or has other items than strings
     */
    static List<String> list(Environment environment, String key) {
        String value = environment.getProperty(key);
        if (value == null) {
            return Binder.get(environment)
                    .bind(key, Bindable.listOf(String.class))
                    .orElse(Collections.emptyList());
        }
        String trimmed = value.trim();
        if (!trimmed.startsWith("[")) {
            return Arrays.stream(StringUtils.commaDelimitedListToStringArray(value))
                    .map(String::trim)
                    .collect(Collectors.toList());
        }
        try {
            JsonNode node = JSON.readTree(trimmed);
            List<String> result = new ArrayList<>();
            for (JsonNode item : node) {
                if (!item.isString()) {
                    throw new IllegalStateException(key + " must be a JSON array of strings");
                }
                result.add(item.asString());
            }
            return result;
        } catch (JacksonException e) {
            throw new IllegalStateException(key + " must be a JSON array of strings");
        }
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static List<String> unmodifiable(List<String> values) {
        return values == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(values));
    }

    /** The issuer of the users' tokens (validated at the user info endpoint), or null */
    public String getIssuer() {
        return issuer;
    }

    /** The name of the required claim, or null if it is off */
    public String getClaimName() {
        return claimName;
    }

    public List<String> getClaimValues() {
        return claimValues;
    }

    public List<String> getClaimPrefixes() {
        return claimPrefixes;
    }

    /** The AuthZEN evaluation URL of the decision service, or null for the built-in rules */
    public String getUrl() {
        return url;
    }

    /** The timeout as set, or null for the default */
    public String getTimeout() {
        return timeout;
    }

    public List<String> getClaims() {
        return claims;
    }

    public String getCredentialFile() {
        return credentialFile;
    }
}
