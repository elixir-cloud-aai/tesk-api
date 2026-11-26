package uk.ac.ebi.tsc.tesk.config.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration of the service clients ({@link ServiceClients}), the only settings left under
 * {@code tesk.api.authorisation}: the group model is removed, and its settings make TESK API refuse
 * to start ({@link RemovedSettings}).
 *
 * @author Ania Niewielska &lt;aniewielska@ebi.ac.uk&gt;
 */
@Configuration
@ConfigurationProperties(prefix = "tesk.api.authorisation")
public class AuthorisationProperties {

    private final ServiceClients serviceClients = new ServiceClients();

    /**
     * Access tokens of services (issue #153): if enabled, a JWT access token that the issuer below
     * signed for one of the listed clients (OAuth 2.0 client credentials grant) is checked locally,
     * against the issuer's keys, instead of at the user info endpoint, and authenticates that
     * client as a principal of its own (see {@link ServiceClientTokenServices}). Other tokens are
     * validated at the user info endpoint, as before.
     */
    public static class ServiceClients {
        /** Whether tokens of the listed clients are accepted */
        private boolean enabled = false;

        /** Issuer of the tokens, compared exactly with their {@code iss} claim */
        private String issuer;

        /** JWKS URI of the issuer; if unset, from the issuer's OpenID Connect discovery */
        private String jwksUri;

        /** Claim that names the client (RFC 9068: {@code client_id}; some providers: azp) */
        private String clientClaim = "client_id";

        /**
         * The clients whose tokens are accepted, with the audiences of each (issue #215): a JSON
         * object from client ID (compared exactly) to the client's settings, {@code {"audiences":
         * [...]}} (the token's {@code aud} must be or contain one of them) or {@code
         * {"allowAnyAudience": true}} (any audience, also tokens meant for other services: for
         * testing or setting up a federation, warned at startup); checked by {@link
         * ServiceClientTokenServices#clients(String)}
         */
        private String clients;

        /** Whether the token's header must have {@code typ} {@code at+jwt} (RFC 9068) */
        private boolean requireAccessTokenType = true;

        /**
         * Whether the token's {@code sub} must be the client's ID, as for tokens of the client
         * credentials grant (RFC 9068, section 2.2); tokens of users obtained through a listed
         * client have the user's {@code sub} and are then validated as user tokens
         */
        private boolean requireClientSubject = true;

        /** Leeway for {@code exp}, {@code nbf} and {@code iat} (in the future), in seconds */
        private int clockSkewSeconds = 60;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getJwksUri() {
            return jwksUri;
        }

        public void setJwksUri(String jwksUri) {
            this.jwksUri = jwksUri;
        }

        public String getClientClaim() {
            return clientClaim;
        }

        public void setClientClaim(String clientClaim) {
            this.clientClaim = clientClaim;
        }

        public String getClients() {
            return clients;
        }

        public void setClients(String clients) {
            this.clients = clients;
        }

        public boolean isRequireAccessTokenType() {
            return requireAccessTokenType;
        }

        public void setRequireAccessTokenType(boolean requireAccessTokenType) {
            this.requireAccessTokenType = requireAccessTokenType;
        }

        public boolean isRequireClientSubject() {
            return requireClientSubject;
        }

        public void setRequireClientSubject(boolean requireClientSubject) {
            this.requireClientSubject = requireClientSubject;
        }

        public int getClockSkewSeconds() {
            return clockSkewSeconds;
        }

        public void setClockSkewSeconds(int clockSkewSeconds) {
            this.clockSkewSeconds = clockSkewSeconds;
        }
    }

    public ServiceClients getServiceClients() {
        return serviceClients;
    }
}
