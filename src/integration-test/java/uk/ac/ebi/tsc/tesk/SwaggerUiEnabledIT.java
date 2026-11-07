package uk.ac.ebi.tsc.tesk;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.ac.ebi.tsc.tesk.UrlConstants.API_DOCS_URL;
import static uk.ac.ebi.tsc.tesk.UrlConstants.HOME_URL;
import static uk.ac.ebi.tsc.tesk.UrlConstants.SWAGGER_UI_URLS;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;

/**
 * With {@code tesk.api.swagger-ui.enabled=true}, the Swagger UI and its static resources are
 * served, and the home page redirects to the UI
 */
@RunWith(SpringRunner.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        locations = {"classpath:application.properties"},
        properties = {"spring.profiles.active=noauth", "tesk.api.swagger-ui.enabled=true"})
public class SwaggerUiEnabledIT {

    @Autowired private MockMvc mvc;

    @Test
    public void uiAndStaticResources_areOk() throws Exception {
        for (String path : SWAGGER_UI_URLS) {
            this.mvc.perform(get(path)).andExpect(status().isOk());
        }
    }

    @Test
    public void home_redirectsToUi() throws Exception {
        this.mvc
                .perform(get(HOME_URL))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("swagger-ui/index.html"));
    }

    @Test
    public void apiDocs_isOk() throws Exception {
        this.mvc.perform(get(API_DOCS_URL)).andExpect(status().isOk());
    }
}
