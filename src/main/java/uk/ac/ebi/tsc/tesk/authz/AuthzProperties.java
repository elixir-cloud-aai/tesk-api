package uk.ac.ebi.tsc.tesk.authz;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the decision service ({@code tesk.api.authz.*}), which decides who may create tasks
 * (OpenID AuthZEN 1.0 Access Evaluation, profile {@code tes-authzen/3} of tes-authz). Required with
 * authentication; checked at startup ({@link AuthzConfiguration}).
 */
// registered with the auth profile (AuthzConfiguration), documented in configuration.md
@ConfigurationProperties(prefix = "tesk.api.authz")
public class AuthzProperties {

    private DecisionService decisionService = new DecisionService();

    /** The decision service */
    public static class DecisionService {

        /** The full URL of its evaluation endpoint (e.g. {@code .../access/v1/evaluation}) */
        private String url;

        /** How long a decision may take as a whole, in seconds */
        private int timeout = 2;

        /**
         * The claims of the caller that the decision service gets ({@code
         * subject.properties.claims}): from the access token, else from the user info endpoint of
         * the token's issuer (looked up on create only, never for service clients)
         */
        private List<String> claims = new ArrayList<>();

        /** A file with a bearer credential that TESK API sends to the decision service */
        private String credentialFile;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public int getTimeout() {
            return timeout;
        }

        public void setTimeout(int timeout) {
            this.timeout = timeout;
        }

        public List<String> getClaims() {
            return claims;
        }

        public void setClaims(List<String> claims) {
            this.claims = claims;
        }

        public String getCredentialFile() {
            return credentialFile;
        }

        public void setCredentialFile(String credentialFile) {
            this.credentialFile = credentialFile;
        }
    }

    public DecisionService getDecisionService() {
        return decisionService;
    }

    public void setDecisionService(DecisionService decisionService) {
        this.decisionService = decisionService;
    }
}
