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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.ac.ebi.tsc.tesk.UrlConstants.SERVICE_INFO_URL;

/**
 * Without authentication there is no token to check, so the setting that requires a token has no effect
 */
@RunWith(SpringRunner.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = {"classpath:application.properties"},
        properties = {"spring.profiles.active=noauth",
                "tesk.api.service-info.public=false"})
public class ServiceInfoNoAuthIT {

    @Autowired
    private MockMvc mvc;

    @Test
    public void withoutToken_isOk() throws Exception {
        this.mvc.perform(get(SERVICE_INFO_URL))
                .andExpect(status().isOk());
    }
}
