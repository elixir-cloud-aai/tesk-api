package uk.ac.ebi.tsc.tesk.config.swagger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.mock.env.MockEnvironment;

/**
 * The setting {@code tesk.api.swagger-ui.enabled} takes only {@code true} or {@code false}, so that
 * the Swagger UI, its static resources and its configuration are either all served or none of them
 */
public class SwaggerUiDisabledConditionTest {

    private static boolean matches(String value) {
        MockEnvironment environment = new MockEnvironment();
        if (value != null) {
            environment.setProperty(SwaggerUiDisabledCondition.PROPERTY, value);
        }
        ConditionContext context = mock(ConditionContext.class);
        when(context.getEnvironment()).thenReturn(environment);
        return new SwaggerUiDisabledCondition().matches(context, null);
    }

    @Test
    public void unset_isDisabled() {
        assertTrue(matches(null));
    }

    @Test
    public void false_isDisabled() {
        assertTrue(matches("false"));
        assertTrue(matches("FALSE"));
    }

    @Test
    public void true_isEnabled() {
        assertFalse(matches("true"));
        assertFalse(matches("True"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void off_isRejected() {
        matches("off");
    }

    @Test(expected = IllegalArgumentException.class)
    public void yes_isRejected() {
        matches("yes");
    }

    @Test(expected = IllegalArgumentException.class)
    public void empty_isRejected() {
        matches("");
    }
}
