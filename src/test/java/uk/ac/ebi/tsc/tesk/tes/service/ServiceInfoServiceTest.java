package uk.ac.ebi.tsc.tesk.tes.service;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.boot.test.system.OutputCaptureRule;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import uk.ac.ebi.tsc.tesk.tes.model.ServiceOrganization;
import uk.ac.ebi.tsc.tesk.tes.model.TesServiceInfo;

/**
 * @author aniewielska
 * @since 09/04/2021
 */
public class ServiceInfoServiceTest {

    @Rule public OutputCaptureRule output = new OutputCaptureRule();

    private Resource serviceInfoFile = new ClassPathResource("service-info.yaml");
    private ServiceInfoService subject = new ServiceInfoService(serviceInfoFile);

    @Test
    public void getServiceInfo() throws URISyntaxException {
        subject.setUp();
        TesServiceInfo result = subject.getServiceInfo();
        TesServiceInfo expected =
                new TesServiceInfo()
                        .name("example application")
                        .createdAt(
                                LocalDateTime.parse("2001-12-14T21:59:00").atOffset(ZoneOffset.UTC))
                        .organization(
                                new ServiceOrganization().name("EBI").url("http://example.com"));
        assertThat(result, is(expected));
        subject.loadYaml();
        assertThat(result, is(expected));
    }

    /** A file of a deployment may list backend parameters, a field of TES 1.1 (issue #145) */
    @Test
    public void getServiceInfo_backendParameters() {
        ServiceInfoService service =
                new ServiceInfoService(
                        new ClassPathResource("service-info-backend-parameters.yaml"));
        service.setUp();
        TesServiceInfo result = service.getServiceInfo();
        assertThat(result.getName(), is("example application"));
        assertThat(result.getTesResourcesBackendParameters(), is(Arrays.asList("VmSize", "Queue")));
        // TESK supports none, whatever the file lists
        assertThat(output.getOut(), containsString("set it to []"));
    }
}
