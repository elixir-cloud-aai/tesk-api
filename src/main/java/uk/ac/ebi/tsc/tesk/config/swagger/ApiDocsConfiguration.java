package uk.ac.ebi.tsc.tesk.config.swagger;

import io.swagger.v3.oas.models.media.Schema;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The API description (springdoc), with any profile */
@Configuration
public class ApiDocsConfiguration {

    /** The properties of TesExecutor that are only written (in requests), by their JSON names */
    private static final Map<String, String> WRITE_ONLY_EXECUTOR_PROPERTIES =
            Map.of("dockerLogin", "docker_login", "dockerPass", "docker_pass");

    /**
     * Names the write-only properties of TesExecutor as in JSON: swagger-core takes their names
     * from the getters (e.g. {@code dockerLogin}), as Jackson leaves their getters out of
     * serialization. Keeps the order of the properties.
     */
    @Bean
    public OpenApiCustomizer writeOnlyPropertyNames() {
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return;
            }
            Schema<?> executor = openApi.getComponents().getSchemas().get("TesExecutor");
            if (executor == null || executor.getProperties() == null) {
                return;
            }
            Map<String, Schema> properties = new LinkedHashMap<>();
            executor.getProperties()
                    .forEach(
                            (name, property) ->
                                    properties.put(
                                            WRITE_ONLY_EXECUTOR_PROPERTIES.getOrDefault(name, name),
                                            property));
            executor.setProperties(properties);
        };
    }
}
