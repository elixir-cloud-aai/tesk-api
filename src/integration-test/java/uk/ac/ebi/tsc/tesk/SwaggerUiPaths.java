package uk.ac.ebi.tsc.tesk;

/**
 * Paths of the Swagger UI, its static resources (served by Springfox and by the static resource mappings of
 * Spring Boot) and the API description, for the tests of the setting {@code tesk.api.swagger-ui.enabled}
 */
public final class SwaggerUiPaths {

    public static final String HOME = "/";
    public static final String API_DOCS = "/v2/api-docs";
    public static final String[] UI_AND_STATIC_RESOURCES = {
            "/swagger-ui/index.html",
            "/swagger-ui/swagger-ui.css",
            "/webjars/springfox-swagger-ui/index.html",
            "/webjars/springfox-swagger-ui/swagger-ui.css"};

    private SwaggerUiPaths() {
    }
}
