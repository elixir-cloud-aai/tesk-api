package uk.ac.ebi.tsc.tesk.config.swagger;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the API description also at its former path {@code /v2/api-docs} (Springfox, until 2.0.0),
 * for clients that read it there: the same OpenAPI 3 description as at {@code /v3/api-docs}
 * (springdoc). Deprecated: to be dropped in the next minor or major release (issue #188; see {@code
 * docs/upgrading.md}).
 */
@Hidden
@Controller
public class ApiDocsAliasController {

    /** The former path of the API description */
    public static final String ALIAS = "/v2/api-docs";

    /** The path of the API description ({@code springdoc.api-docs.path}) */
    public static final String API_DOCS = "/v3/api-docs";

    @GetMapping(ALIAS)
    public String apiDocs() {
        return "forward:" + API_DOCS;
    }
}
