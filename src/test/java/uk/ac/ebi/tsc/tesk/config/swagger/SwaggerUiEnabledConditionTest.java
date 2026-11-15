package uk.ac.ebi.tsc.tesk.config.swagger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.mock.env.MockEnvironment;

/**
 * The home page redirects to the Swagger UI only with {@code tesk.api.swagger-ui.enabled=true};
 * other values than {@code true} or {@code false} stop the app
 */
public class SwaggerUiEnabledConditionTest {

    private static boolean matches(String value) {
        MockEnvironment environment = new MockEnvironment();
        if (value != null) {
            environment.setProperty(SwaggerUiDisabledCondition.PROPERTY, value);
        }
        ConditionContext context = mock(ConditionContext.class);
        when(context.getEnvironment()).thenReturn(environment);
        return new SwaggerUiEnabledCondition().matches(context, null);
    }

    @Test
    public void unsetOrFalse_isDisabled() {
        assertFalse(matches(null));
        assertFalse(matches("false"));
    }

    @Test
    public void true_isEnabled() {
        assertTrue(matches("true"));
        assertTrue(matches("TRUE"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void yes_isRejected() {
        matches("yes");
    }
}
