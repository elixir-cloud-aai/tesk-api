package uk.ac.ebi.tsc.tesk.config.swagger;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;

/** The write-only properties of TesExecutor have their JSON names in the API description */
public class ApiDocsConfigurationTest {

    private final OpenApiCustomizer customizer =
            new ApiDocsConfiguration().writeOnlyPropertyNames();

    @Test
    public void writeOnlyProperties_haveTheirJsonNames_inOrder() {
        Schema<?> executor =
                new ObjectSchema()
                        .addProperty("image", new StringSchema())
                        .addProperty("dockerLogin", new StringSchema().writeOnly(true))
                        .addProperty("dockerPass", new StringSchema().writeOnly(true))
                        .addProperty("docker_registry", new StringSchema());
        OpenAPI api =
                new OpenAPI().components(new Components().addSchemas("TesExecutor", executor));

        customizer.customise(api);

        assertThat(api.getComponents().getSchemas().get("TesExecutor").getProperties())
                .containsOnlyKeys("image", "docker_login", "docker_pass", "docker_registry");
        assertThat(executor.getProperties().keySet())
                .containsExactly("image", "docker_login", "docker_pass", "docker_registry");
        assertThat(executor.getProperties().get("docker_pass").getWriteOnly()).isTrue();
    }

    /** Descriptions without the schema or its properties are left as they are */
    @Test
    public void otherDescriptions_areUnchanged() {
        customizer.customise(new OpenAPI());
        customizer.customise(new OpenAPI().components(new Components()));
        OpenAPI withoutExecutor =
                new OpenAPI()
                        .components(new Components().addSchemas("TesTask", new ObjectSchema()));
        customizer.customise(withoutExecutor);
        assertThat(withoutExecutor.getComponents().getSchemas()).containsOnlyKeys("TesTask");
        Schema<?> executor = new ObjectSchema();
        customizer.customise(
                new OpenAPI().components(new Components().addSchemas("TesExecutor", executor)));
        assertThat(executor.getProperties()).isNull();
    }
}
