package uk.ac.ebi.tsc.tesk.tes.service;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
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
                                new ServiceOrganization().name("EBI").url("http://example.com"))
                        // TESK supports no backend parameters, also if the file doesn't say so
                        .tesResourcesBackendParameters(Collections.emptyList());
        assertThat(result, is(expected));
        subject.loadYaml();
        assertThat(result, is(expected));
    }

    /**
     * A file of a deployment may have the key of TES 1.1, but TESK supports no backend parameters:
     * service info lists none, whatever the file lists, and a warning says so (issue #145)
     */
    @Test
    public void getServiceInfo_backendParameters() {
        ServiceInfoService service =
                new ServiceInfoService(
                        new ClassPathResource("service-info-backend-parameters.yaml"));
        service.setUp();
        TesServiceInfo result = service.getServiceInfo();
        assertThat(result.getName(), is("example application"));
        assertThat(result.getTesResourcesBackendParameters(), is(Collections.emptyList()));
        assertThat(output.getOut(), containsString("set it to []"));
    }

    /** The list of backend parameters counts for equality (issue #145) */
    @Test
    public void serviceInfo_backendParametersEquality() {
        TesServiceInfo info =
                new TesServiceInfo().tesResourcesBackendParameters(Collections.emptyList());
        TesServiceInfo same = new TesServiceInfo();
        same.setTesResourcesBackendParameters(Collections.emptyList());
        assertThat(info, is(same));
        assertThat(info.hashCode(), is(same.hashCode()));
        assertThat(info.toString(), containsString("tesResourcesBackendParameters: []"));
        assertThat(info, is(not(new TesServiceInfo())));
        assertThat(info, is(not(new TesServiceInfo().storage(Collections.emptyList()))));
    }
}
