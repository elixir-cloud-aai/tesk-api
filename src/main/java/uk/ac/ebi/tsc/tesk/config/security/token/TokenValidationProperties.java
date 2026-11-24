package uk.ac.ebi.tsc.tesk.config.security.token;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the validation of access tokens ({@code tesk.api.auth.*}). The defaults are the
 * secure ones; issuers, their audiences and service clients have no defaults.
 */
// registered, and documented in configuration.md, when authentication is switched to it
@ConfigurationProperties(prefix = "tesk.api.auth")
public class TokenValidationProperties {

    /** The kinds of token validation (only local validation of JWT access tokens for now) */
    public enum Validator {
        /** JWT access tokens (RFC 9068), validated locally against the issuers' keys */
        JWT
    }

    /** How tokens are validated */
    private Validator tokenValidator = Validator.JWT;

    /** Accepted signature algorithms (asymmetric ones only) */
    private List<String> algorithms = new ArrayList<>(List.of("RS256", "ES256"));

    /** Whether tokens must have the header {@code typ} {@code at+jwt} (RFC 9068) */
    private boolean requireAccessTokenType = true;

    /** Leeway for {@code exp}, {@code nbf} and {@code iat}, in seconds */
    private int clockSkewSeconds = 60;

    /** Age (by {@code iat}) beyond which tokens are refused, whatever their {@code exp} */
    private int maxTokenAgeSeconds = 86_400;

    /** Connect and read timeouts of the requests to the issuers (discovery, keys), in seconds */
    private int timeoutSeconds = 10;

    /** The accepted issuers */
    private List<Issuer> issuers = new ArrayList<>();

    /** An accepted issuer */
    public static class Issuer {
        /** The issuer's URL, exactly as in {@code iss} (also the trailing slash) */
        private String url;

        /** The URL of its keys; by default the {@code jwks_uri} of its discovery document */
        private String jwksUri;

        /**
         * Accepted audiences of user tokens: the client IDs of the front ends through which users
         * call TESK API ({@code aud} must be or contain one of them)
         */
        private List<String> audiences = new ArrayList<>();

        /** The claim that names the client a token was issued to */
        private String clientClaim = "client_id";

        /** The service clients whose own tokens (client credentials grant) are accepted */
        private List<ServiceClient> serviceClients = new ArrayList<>();

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getJwksUri() {
            return jwksUri;
        }

        public void setJwksUri(String jwksUri) {
            this.jwksUri = jwksUri;
        }

        public List<String> getAudiences() {
            return audiences;
        }

        public void setAudiences(List<String> audiences) {
            this.audiences = audiences;
        }

        public String getClientClaim() {
            return clientClaim;
        }

        public void setClientClaim(String clientClaim) {
            this.clientClaim = clientClaim;
        }

        public List<ServiceClient> getServiceClients() {
            return serviceClients;
        }

        public void setServiceClients(List<ServiceClient> serviceClients) {
            this.serviceClients = serviceClients;
        }
    }

    /**
     * A service client: its token is accepted if both the client claim and {@code sub} are its ID,
     * and {@code aud} is or contains its ID. An object, so that settings per client can be added
     * later.
     */
    public static class ServiceClient {
        /** The client's ID */
        private String clientId;

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }
    }

    public Validator getTokenValidator() {
        return tokenValidator;
    }

    public void setTokenValidator(Validator tokenValidator) {
        this.tokenValidator = tokenValidator;
    }

    public List<String> getAlgorithms() {
        return algorithms;
    }

    public void setAlgorithms(List<String> algorithms) {
        this.algorithms = algorithms;
    }

    public boolean isRequireAccessTokenType() {
        return requireAccessTokenType;
    }

    public void setRequireAccessTokenType(boolean requireAccessTokenType) {
        this.requireAccessTokenType = requireAccessTokenType;
    }

    public int getClockSkewSeconds() {
        return clockSkewSeconds;
    }

    public void setClockSkewSeconds(int clockSkewSeconds) {
        this.clockSkewSeconds = clockSkewSeconds;
    }

    public int getMaxTokenAgeSeconds() {
        return maxTokenAgeSeconds;
    }

    public void setMaxTokenAgeSeconds(int maxTokenAgeSeconds) {
        this.maxTokenAgeSeconds = maxTokenAgeSeconds;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public List<Issuer> getIssuers() {
        return issuers;
    }

    public void setIssuers(List<Issuer> issuers) {
        this.issuers = issuers;
    }
}
