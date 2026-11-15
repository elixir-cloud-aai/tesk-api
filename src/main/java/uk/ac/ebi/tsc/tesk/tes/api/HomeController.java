package uk.ac.ebi.tsc.tesk.tes.api;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import uk.ac.ebi.tsc.tesk.config.swagger.SwaggerUiEnabledCondition;

/**
 * Home redirection to swagger api documentation, only if the Swagger UI is on ({@code
 * tesk.api.swagger-ui.enabled}, which takes only {@code true} or {@code false}: see {@link
 * SwaggerUiEnabledCondition}); not part of the API description
 */
@Hidden
@Controller
@Conditional(SwaggerUiEnabledCondition.class)
public class HomeController {

    @RequestMapping(value = "/")
    public String index() {
        return "redirect:swagger-ui/index.html";
    }
}
