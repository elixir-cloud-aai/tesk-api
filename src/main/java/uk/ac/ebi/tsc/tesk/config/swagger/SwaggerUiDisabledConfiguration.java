package uk.ac.ebi.tsc.tesk.config.swagger;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import springfox.documentation.swagger.web.ApiResourceController;

import java.util.Arrays;

/**
 * Without the Swagger UI ({@code tesk.api.swagger-ui.enabled} not {@code true}), also leaves out the endpoints that
 * configure it ({@code /swagger-resources/**}), which Springfox maps regardless of its own setting
 * {@code springfox.documentation.swagger-ui.enabled}. The UI itself and the static resources follow the setting
 * through {@code application.properties}; the API description ({@code /v2/api-docs}) is served either way.
 * {@link SwaggerUiDisabledCondition} also rejects values other than {@code true} or {@code false}. The integration
 * tests {@code SwaggerUi*IT} check that the controller is left out, e.g. after updates of Springfox.
 */
@Configuration
@Conditional(SwaggerUiDisabledCondition.class)
public class SwaggerUiDisabledConfiguration {

    @Bean
    public static BeanDefinitionRegistryPostProcessor removeSwaggerUiResources() {
        return new BeanDefinitionRegistryPostProcessor() {
            @Override
            public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
                Arrays.stream(registry.getBeanDefinitionNames())
                        .filter(name -> ApiResourceController.class.getName()
                                .equals(registry.getBeanDefinition(name).getBeanClassName()))
                        .forEach(registry::removeBeanDefinition);
            }

            @Override
            public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
            }
        };
    }
}
