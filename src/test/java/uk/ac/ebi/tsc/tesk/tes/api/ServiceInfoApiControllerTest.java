package uk.ac.ebi.tsc.tesk.tes.api;

import static org.junit.Assert.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.ac.ebi.tsc.tesk.UrlConstants.SERVICE_INFO_URL;

import org.junit.Before;
import org.junit.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uk.ac.ebi.tsc.tesk.tes.service.ServiceInfoService;

/**
 * Service info from the built-in {@code service-info.yaml} (the test resources have their own file
 * of that name, so it is read from the source tree)
 */
public class ServiceInfoApiControllerTest {

    private MockMvc mvc;

    @Before
    public void setUp() {
        ServiceInfoService service =
                new ServiceInfoService(
                        new FileSystemResource("src/main/resources/service-info.yaml"));
        service.setUp();
        assertNotNull("built-in service-info.yaml could not be parsed", service.getServiceInfo());
        mvc =
                MockMvcBuilders.standaloneSetup(new ServiceInfoApiController(null, service))
                        .addPlaceholderValue(
                                "openapi.taskExecutionService.base-path", "/ga4gh/tes/v1")
                        .build();
    }

    /** GA4GH specifications use semantic versioning: TES 1.1.0, as a string */
    @Test
    public void getServiceInfo_reportsTesTypeVersionAsString() throws Exception {
        mvc.perform(get(SERVICE_INFO_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type.group").value("org.ga4gh"))
                .andExpect(jsonPath("$.type.artifact").value("tes"))
                .andExpect(jsonPath("$.type.version").value("1.1.0"));
    }
}
