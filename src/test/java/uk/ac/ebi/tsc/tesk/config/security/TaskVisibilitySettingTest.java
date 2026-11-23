package uk.ac.ebi.tsc.tesk.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

/** Binding of the setting tesk.api.authorisation.task-visibility (issue #218) */
public class TaskVisibilitySettingTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                    .withUserConfiguration(AuthorisationProperties.class);

    private static final String PROPERTY = "tesk.api.authorisation.task-visibility";

    /** Without the setting, tasks are visible to their owner only */
    @Test
    public void unset_owner() {
        contextRunner.run(
                context -> assertThat(visibility(context)).isEqualTo(TaskVisibility.OWNER));
    }

    /** The values bind in any case, as in the environment variable */
    @Test
    public void values_anyCase() {
        contextRunner
                .withPropertyValues(PROPERTY + "=group")
                .run(context -> assertThat(visibility(context)).isEqualTo(TaskVisibility.GROUP));
        contextRunner
                .withPropertyValues(PROPERTY + "=OWNER")
                .run(context -> assertThat(visibility(context)).isEqualTo(TaskVisibility.OWNER));
    }

    /** An empty value keeps the default, rather than falling back to group visibility */
    @Test
    public void empty_owner() {
        contextRunner
                .withPropertyValues(PROPERTY + "=")
                .run(context -> assertThat(visibility(context)).isEqualTo(TaskVisibility.OWNER));
    }

    /** Other values stop the application at startup */
    @Test
    public void invalidValue_failsStartup() {
        contextRunner
                .withPropertyValues(PROPERTY + "=all")
                .run(context -> assertThat(context).hasFailed());
    }

    private static TaskVisibility visibility(ApplicationContext context) {
        return context.getBean(AuthorisationProperties.class).getTaskVisibility();
    }
}
