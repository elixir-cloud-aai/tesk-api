package uk.ac.ebi.tsc.tesk;

/**
 * @author aniewielska
 * @since 29/03/2021
 */
public class UrlConstants {
    public static final String BASE_URL = "/ga4gh/tes/v1";
    public static final String TASK_URL = BASE_URL + "/tasks";
    public static final String SERVICE_INFO_URL = BASE_URL + "/service-info";
    public static final String HOME_URL = "/";
    public static final String API_DOCS_URL = "/v2/api-docs";
    /**
     * The Swagger UI, its static resources (served by Springfox and by the static resource mappings
     * of Spring Boot) and the endpoints that configure it, all of which follow the setting {@code
     * tesk.api.swagger-ui.enabled}
     */
    public static final String[] SWAGGER_UI_URLS = {
        "/swagger-ui/index.html",
        "/swagger-ui/swagger-ui.css",
        "/webjars/springfox-swagger-ui/index.html",
        "/webjars/springfox-swagger-ui/swagger-ui.css",
        "/swagger-resources",
        "/swagger-resources/configuration/ui",
        "/swagger-resources/configuration/security"
    };
}
