package uk.ac.ebi.tsc.tesk.config.swagger;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches if the Swagger UI is off: {@code tesk.api.swagger-ui.enabled} unset or {@code false}.
 * Rejects any value other than {@code true} or {@code false} (ignoring case), so that the app fails
 * to start instead of serving only some of the UI: Spring and springdoc read other spellings of
 * booleans (e.g. {@code off}, {@code yes}) differently
 */
public class SwaggerUiDisabledCondition implements Condition {

    static final String PROPERTY = "tesk.api.swagger-ui.enabled";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return !isEnabled(context.getEnvironment());
    }

    /**
     * Whether the Swagger UI is on, rejecting values other than {@code true} and {@code false}
     *
     * @throws IllegalArgumentException for any other value
     */
    static boolean isEnabled(Environment environment) {
        String value = environment.getProperty(PROPERTY);
        if (value == null || "false".equalsIgnoreCase(value)) {
            return false;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        throw new IllegalArgumentException(
                "Invalid value '"
                        + value
                        + "' of "
                        + PROPERTY
                        + " (TESK_API_SWAGGER_UI_ENABLED): use true or false");
    }
}
