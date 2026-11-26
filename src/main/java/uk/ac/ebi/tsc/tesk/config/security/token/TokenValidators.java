package uk.ac.ebi.tsc.tesk.config.security.token;

import java.util.Objects;

/**
 * Creates the {@link TokenValidator} that the settings select ({@code
 * tesk.api.auth.token-validator})
 */
public final class TokenValidators {

    private TokenValidators() {}

    /**
     * @param properties the settings
     * @return the validator of the selected kind
     * @throws IllegalStateException if the settings of that validator are incomplete or invalid
     */
    public static TokenValidator create(TokenValidationProperties properties) {
        TokenValidationProperties.Validator kind =
                Objects.requireNonNull(
                        properties.getTokenValidator(), "tesk.api.auth.token-validator not set");
        return switch (kind) {
            case JWT -> new JwtAccessTokenValidator(properties);
        };
    }
}
