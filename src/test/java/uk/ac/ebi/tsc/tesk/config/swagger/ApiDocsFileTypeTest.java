package uk.ac.ebi.tsc.tesk.config.swagger;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The API description documents the type of inputs and outputs as optional, with its two values, in
 * requests and responses (TES 1.1, issue #146), although the models read it from JSON through a
 * String setter.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(properties = {"tesk.api.swagger-ui.enabled=true"})
@AutoConfigureMockMvc
public class ApiDocsFileTypeTest {

    @Autowired private MockMvc mvc;

    @Test
    public void typeIsOptionalWithTwoValues() throws Exception {
        ResultActions docs = this.mvc.perform(get("/v2/api-docs")).andExpect(status().isOk());
        for (String model :
                new String[] {"TesInputReq", "TesInputRes", "TesOutputReq", "TesOutputRes"}) {
            String definition = "$.definitions." + model;
            docs.andExpect(jsonPath(definition + ".required", not(hasItem("type"))))
                    .andExpect(
                            jsonPath(
                                    definition + ".properties.type.enum",
                                    containsInAnyOrder("FILE", "DIRECTORY")));
        }
    }
}
