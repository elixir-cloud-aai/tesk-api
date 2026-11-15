package uk.ac.ebi.tsc.tesk.config.swagger;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches if the Swagger UI is on: {@code tesk.api.swagger-ui.enabled} is {@code true}. The
 * opposite of {@link SwaggerUiDisabledCondition}, with its check, which rejects any value other
 * than {@code true} or {@code false} (ignoring case).
 */
public class SwaggerUiEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return SwaggerUiDisabledCondition.isEnabled(context.getEnvironment());
    }
}
