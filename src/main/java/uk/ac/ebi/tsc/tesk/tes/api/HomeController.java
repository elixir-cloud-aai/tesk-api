package uk.ac.ebi.tsc.tesk.tes.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Home redirection to swagger api documentation, only if the Swagger UI is on ({@code tesk.api.swagger-ui.enabled})
 */
@Controller
@ConditionalOnProperty(name = "tesk.api.swagger-ui.enabled", havingValue = "true")
public class HomeController {

    @RequestMapping(value = "/")
    public String index() {
        return "redirect:swagger-ui/index.html";
    }
}
