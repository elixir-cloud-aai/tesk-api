package uk.ac.ebi.tsc.tesk.tes.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import uk.ac.ebi.tsc.tesk.tes.model.ServiceOrganization;
import uk.ac.ebi.tsc.tesk.tes.model.TesServiceInfo;

/**
 * @author aniewielska
 * @since 09/04/2021
 */
@ExtendWith(OutputCaptureExtension.class)
public class ServiceInfoServiceTest {

    private CapturedOutput output;

    @BeforeEach
    public void captureOutput(CapturedOutput output) {
        this.output = output;
    }

    @TempDir public File folder;

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

    /** The file is read again periodically, so that changes need no restart */
    @Test
    public void reload_readsTheChangedFile() throws IOException {
        File file = new File(folder, "service-info.yaml");
        Files.write(file.toPath(), "name: first\n".getBytes(StandardCharsets.UTF_8));
        ServiceInfoService service = new ServiceInfoService(new FileSystemResource(file));
        service.setUp();
        Files.write(file.toPath(), "name: second\n".getBytes(StandardCharsets.UTF_8));

        service.reload();

        assertThat(service.getServiceInfo().getName(), is("second"));
    }

    /** A file that can't be parsed is logged, and the service info read before is kept */
    @Test
    public void reload_keepsTheServiceInfoIfTheFileIsInvalid() throws IOException {
        File file = new File(folder, "service-info.yaml");
        Files.write(file.toPath(), "name: first\n".getBytes(StandardCharsets.UTF_8));
        ServiceInfoService service = new ServiceInfoService(new FileSystemResource(file));
        service.setUp();
        Files.write(file.toPath(), "name: [unclosed\n".getBytes(StandardCharsets.UTF_8));

        service.reload();

        assertThat(service.getServiceInfo().getName(), is("first"));
        assertThat(output.getOut(), containsString("Unable to parse Service Info Yaml"));
    }
}
