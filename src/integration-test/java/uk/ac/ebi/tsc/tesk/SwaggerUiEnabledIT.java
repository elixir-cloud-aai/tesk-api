package uk.ac.ebi.tsc.tesk;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.ac.ebi.tsc.tesk.SwaggerUiPaths.API_DOCS;
import static uk.ac.ebi.tsc.tesk.SwaggerUiPaths.HOME;
import static uk.ac.ebi.tsc.tesk.SwaggerUiPaths.UI_AND_STATIC_RESOURCES;

/**
 * With {@code tesk.api.swagger-ui.enabled=true}, the Swagger UI and its static resources are served, and the home
 * page redirects to the UI
 */
@RunWith(SpringRunner.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = {"classpath:application.properties"},
        properties = {"spring.profiles.active=noauth",
                "tesk.api.swagger-ui.enabled=true"})
public class SwaggerUiEnabledIT {

    @Autowired
    private MockMvc mvc;

    @Test
    public void uiAndStaticResources_areOk() throws Exception {
        for (String path : UI_AND_STATIC_RESOURCES) {
            this.mvc.perform(get(path))
                    .andExpect(status().isOk());
        }
    }

    @Test
    public void home_redirectsToUi() throws Exception {
        this.mvc.perform(get(HOME))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("swagger-ui/index.html"));
    }

    @Test
    public void apiDocs_isOk() throws Exception {
        this.mvc.perform(get(API_DOCS))
                .andExpect(status().isOk());
    }
}
