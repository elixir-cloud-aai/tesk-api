package uk.ac.ebi.tsc.tesk;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.ac.ebi.tsc.tesk.UrlConstants.API_DOCS_URL;
import static uk.ac.ebi.tsc.tesk.UrlConstants.FORMER_API_DOCS_URL;
import static uk.ac.ebi.tsc.tesk.UrlConstants.HOME_URL;
import static uk.ac.ebi.tsc.tesk.UrlConstants.SWAGGER_UI_URLS;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * By default, the Swagger UI and its static resources are off; the API description stays available
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        locations = {"classpath:application.properties"},
        properties = {"spring.profiles.active=noauth"})
public class SwaggerUiDisabledIT {

    @Autowired private MockMvc mvc;

    @Test
    public void uiAndStaticResources_areNotFound() throws Exception {
        for (String path : SWAGGER_UI_URLS) {
            this.mvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }

    @Test
    public void home_isNotFound() throws Exception {
        this.mvc.perform(get(HOME_URL)).andExpect(status().isNotFound());
    }

    @Test
    public void apiDocs_isOk() throws Exception {
        this.mvc.perform(get(API_DOCS_URL)).andExpect(status().isOk());
    }

    /** The former path of the API description, an alias in 2.0.0, is gone (#188) */
    @Test
    public void formerApiDocs_isNotFound() throws Exception {
        this.mvc.perform(get(FORMER_API_DOCS_URL)).andExpect(status().isNotFound());
    }
}
