package uk.ac.ebi.tsc.tesk.trs;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import jakarta.annotation.PostConstruct;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.client.MockRestServiceServer;
import tools.jackson.databind.ObjectMapper;
import uk.ac.ebi.tsc.tesk.trs.model.ImageData;
import uk.ac.ebi.tsc.tesk.trs.model.ImageType;
import uk.ac.ebi.tsc.tesk.trs.model.ToolVersion;

/**
 * @author aniewielska
 * @since 31/03/2021
 */
@RunWith(SpringRunner.class)
@RestClientTest(TrsToolClient.class)
@TestPropertySource(
        properties = {
            "tesk.api.trs.urlPattern=https://{host}/trs/{id}/versions/{version}",
            "tesk.api.trs.uriPattern=trs://{host}/trs-id/{id}/{version}"
        })
public class TrsToolClientIT {

    @Autowired private TrsToolClient subject;
    @Autowired private MockRestServiceServer server;
    @Autowired private ObjectMapper objectMapper;

    @TestConfiguration
    static class Configuration {
        @Bean
        public TrsProperties trsProperties() {
            return new TrsProperties();
        }
    }

    private String detailsStringWithDocker;
    private String detailsStringWithoutDocker;

    @PostConstruct
    public void setup() throws Exception {
        ToolVersion toolVersion = new ToolVersion();
        ImageData imageData1 = new ImageData();
        imageData1.setImageType(ImageType.Docker);
        imageData1.setImageName("ubuntu:latest");
        toolVersion.getImages().add(imageData1);
        ImageData imageData2 = new ImageData();
        imageData2.setImageType(ImageType.Conda);
        imageData2.setImageName("sthElse");
        toolVersion.getImages().add(imageData2);
        detailsStringWithDocker = objectMapper.writeValueAsString(toolVersion);
        toolVersion.getImages().remove(0);
        detailsStringWithoutDocker = objectMapper.writeValueAsString(toolVersion);
    }

    @Before
    public void setUp() {
        this.server.reset();
    }

    @Test
    public void getDockerImageForToolVersionURI_success() {
        this.server
                .expect(requestTo("https://trsHost/trs/toolId/versions/toolVersion"))
                .andRespond(withSuccess(detailsStringWithDocker, MediaType.APPLICATION_JSON));
        String result =
                subject.getDockerImageForToolVersionURI("trs://trsHost/trs-id/toolId/toolVersion");
        assertThat(result, is("ubuntu:latest"));
    }

    @Test
    public void getDockerImageForToolVersionURI_wrongUri() {
        String result = subject.getDockerImageForToolVersionURI("sth");
        assertThat(result, is("sth"));
    }

    @Test
    public void getDockerImageForToolVersionURI_noDocker() {
        this.server
                .expect(requestTo("https://trsHost/trs/toolId2/versions/toolVersion2"))
                .andRespond(withSuccess(detailsStringWithoutDocker, MediaType.APPLICATION_JSON));
        String result =
                subject.getDockerImageForToolVersionURI(
                        "trs://trsHost/trs-id/toolId2/toolVersion2");
        assertThat(result, is("trs://trsHost/trs-id/toolId2/toolVersion2"));
    }

    @Test
    public void getDockerImageForToolVersion_success() {
        this.server
                .expect(requestTo("https://trsHost/trs/toolId/versions/toolVersion"))
                .andRespond(withSuccess(detailsStringWithDocker, MediaType.APPLICATION_JSON));
        Optional<String> result =
                subject.getDockerImageForToolVersion("trsHost", "toolId", "toolVersion");
        assertThat(result.get(), is("ubuntu:latest"));
    }

    @Test
    public void getDockerImageForToolVersion_noDocker() {
        this.server
                .expect(requestTo("https://trsHost/trs/toolId2/versions/toolVersion2"))
                .andRespond(withSuccess(detailsStringWithoutDocker, MediaType.APPLICATION_JSON));
        Optional<String> result =
                subject.getDockerImageForToolVersion("trsHost", "toolId2", "toolVersion2");
        assertThat(result, is(Optional.empty()));
    }

    /** If the TRS fails, the URI is kept as the image (and the executor fails to pull it) */
    @Test
    public void getDockerImageForToolVersion_trsError() {
        this.server
                .expect(requestTo("https://trsHost/trs/toolId/versions/toolVersion"))
                .andRespond(withServerError());
        assertThat(
                subject.getDockerImageForToolVersionURI("trs://trsHost/trs-id/toolId/toolVersion"),
                is("trs://trsHost/trs-id/toolId/toolVersion"));
    }

    /** A response without a body, or without images, gives no image */
    @Test
    public void getDockerImageForToolVersion_noImages() throws Exception {
        this.server
                .expect(requestTo("https://trsHost/trs/toolId/versions/toolVersion"))
                .andRespond(withNoContent());
        assertThat(
                subject.getDockerImageForToolVersion("trsHost", "toolId", "toolVersion"),
                is(Optional.empty()));
        this.server.reset();
        this.server
                .expect(requestTo("https://trsHost/trs/toolId/versions/toolVersion"))
                .andRespond(
                        withSuccess(
                                objectMapper.writeValueAsString(new ToolVersion()),
                                MediaType.APPLICATION_JSON));
        assertThat(
                subject.getDockerImageForToolVersion("trsHost", "toolId", "toolVersion"),
                is(Optional.empty()));
    }

    /** Responses other than success (which don't fail the request, e.g. 304) give no image */
    @Test
    public void getDockerImageForToolVersion_notSuccessful() {
        this.server
                .expect(requestTo("https://trsHost/trs/toolId/versions/toolVersion"))
                .andRespond(
                        withStatus(HttpStatus.NOT_MODIFIED)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(detailsStringWithDocker));
        assertThat(
                subject.getDockerImageForToolVersion("trsHost", "toolId", "toolVersion"),
                is(Optional.empty()));
    }
}
