package uk.ac.ebi.tsc.tesk.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import uk.ac.ebi.tsc.tesk.config.security.User;
import uk.ac.ebi.tsc.tesk.config.security.token.TokenPrincipal;
import uk.ac.ebi.tsc.tesk.config.security.token.TokenValidationProperties;
import uk.ac.ebi.tsc.tesk.tes.exception.AuthorisationUnavailableException;
import uk.ac.ebi.tsc.tesk.tes.model.TaskView;
import uk.ac.ebi.tsc.tesk.tes.model.TesServiceInfo;
import uk.ac.ebi.tsc.tesk.tes.model.TesTask;
import uk.ac.ebi.tsc.tesk.tes.service.ServiceInfoService;
import uk.ac.ebi.tsc.tesk.tes.service.TaskAccess;
import uk.ac.ebi.tsc.tesk.tes.service.TaskAccess.Operation;

/** The startup checks of the decision service's settings ({@code tesk.api.authz.*}) */
public class AuthzConfigurationTest {

    @TempDir Path dir;

    private AuthzProperties properties;

    @BeforeEach
    public void setUp() {
        this.properties = new AuthzProperties();
        this.properties.setDecisionService(new AuthzProperties.DecisionService());
        this.properties
                .getDecisionService()
                .setUrl(" https://pdp.example.org/access/v1/evaluation ");
    }

    private AuthzProperties.DecisionService settings() {
        return this.properties.getDecisionService();
    }

    private String startupError() {
        IllegalStateException e =
                org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalStateException.class,
                        () -> new AuthzConfiguration().decisionServiceClient(this.properties));
        return e.getMessage();
    }

    @Test
    public void defaults() {
        AuthzProperties.DecisionService defaults = new AuthzProperties.DecisionService();
        assertThat(defaults.getUrl()).isNull();
        assertThat(defaults.getTimeout()).isEqualTo(2);
        assertThat(defaults.getClaims()).isEmpty();
        assertThat(defaults.getCredentialFile()).isNull();
    }

    @Test
    public void valid_givesTheUrlWithoutWhiteSpace() {
        assertThat(AuthzConfiguration.check(settings()))
                .isEqualTo("https://pdp.example.org/access/v1/evaluation");
        assertThat(new AuthzConfiguration().decisionServiceClient(this.properties)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    public void noUrl_failsAtStartup(String url) {
        settings().setUrl(url);
        assertThat(startupError()).startsWith("tesk.api.authz.decision-service.url must be set");
        settings().setUrl(null);
        assertThat(startupError()).startsWith("tesk.api.authz.decision-service.url must be set");
    }

    /** Plain http only to the local host (e.g. a sidecar) */
    @Test
    public void plainHttpUrl_failsAtStartup() {
        settings().setUrl("http://pdp.example.org/access/v1/evaluation");
        assertThat(startupError()).contains("decision-service.url must be an https URL");
        settings().setUrl("http://localhost:8181/v1/data/tes/allow");
        assertThat(AuthzConfiguration.check(settings())).startsWith("http://localhost");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 61})
    public void timeoutOutOfRange_failsAtStartup(int timeout) {
        settings().setTimeout(timeout);
        assertThat(startupError()).contains("decision-service.timeout must be between 1 and 60");
    }

    @Test
    public void timeoutAtTheBounds() {
        settings().setTimeout(1);
        AuthzConfiguration.check(settings());
        settings().setTimeout(60);
        AuthzConfiguration.check(settings());
    }

    @Test
    public void blankClaim_failsAtStartup() {
        settings().setClaims(new ArrayList<>(Arrays.asList("eduperson_entitlement", " ")));
        assertThat(startupError()).contains("decision-service.claims must not have blank values");
    }

    @Test
    public void claims_withoutWhiteSpace() {
        settings().setClaims(List.of(" eduperson_entitlement "));
        assertThat(AuthzConfiguration.claims(settings())).containsExactly("eduperson_entitlement");
    }

    @Test
    public void credentialFile_readable() throws Exception {
        Path file = this.dir.resolve("credential");
        Files.write(file, "secret\n".getBytes(StandardCharsets.UTF_8));
        settings().setCredentialFile(" " + file + " ");
        assertThat(new AuthzConfiguration().decisionServiceClient(this.properties)).isNotNull();
    }

    @Test
    public void credentialFile_missing_failsAtStartup() {
        settings().setCredentialFile(this.dir.resolve("missing").toString());
        assertThat(startupError()).contains("decision-service.credential-file can't be read");
    }

    @Test
    public void credentialFile_severalLines_failsAtStartup() throws Exception {
        Path file = this.dir.resolve("lines");
        Files.write(file, "a\nb\n".getBytes(StandardCharsets.UTF_8));
        settings().setCredentialFile(file.toString());
        assertThat(startupError()).contains("credential-file");
    }

    /** The bean of the decision sends the service info's ID as {@code context.tes.service_id} */
    @Test
    @SuppressWarnings("unchecked")
    public void createAuthorisation() throws Exception {
        AuthzConfiguration configuration = new AuthzConfiguration();
        ServiceInfoService serviceInfo = mock(ServiceInfoService.class);
        TesServiceInfo info = new TesServiceInfo();
        info.setId("org.example.tes");
        when(serviceInfo.getServiceInfo()).thenReturn(info);
        DecisionServiceClient decisionService = mock(DecisionServiceClient.class);
        when(decisionService.evaluate(any(), anyString())).thenReturn(true);
        CreateAuthorisation authz =
                configuration.createAuthorisation(
                        decisionService,
                        configuration.userInfoClient(new TokenValidationProperties()),
                        this.properties,
                        serviceInfo);
        TokenPrincipal alice =
                new TokenPrincipal(
                        "https://issuer.example/",
                        "alice",
                        TokenPrincipal.Kind.USER,
                        "portal",
                        Map.of());

        assertThat(
                        authz.mayCreate(
                                new TesTask(),
                                User.builder(alice.owner()).principal(alice).build()))
                .isTrue();
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(decisionService).evaluate(request.capture(), anyString());
        Map<String, Object> context = (Map<String, Object>) request.getValue().get("context");
        assertThat((Map<String, Object>) context.get("tes"))
                .containsEntry("service_id", "org.example.tes");
    }

    @Test
    public void operations_default() {
        assertThat(this.properties.getOperations()).containsExactly("create");
        assertThat(this.properties.getFailure()).isEmpty();
        assertThat(AuthzConfiguration.operations(this.properties.getOperations()))
                .containsExactly(Operation.CREATE);
    }

    @Test
    public void operations_all() {
        assertThat(AuthzConfiguration.operations(List.of(" cancel", "create", "get ", "list")))
                .containsExactlyInAnyOrder(
                        Operation.CREATE, Operation.GET, Operation.LIST, Operation.CANCEL);
    }

    @Test
    public void operations_withoutCreate_failsAtStartup() {
        assertThatThrownBy(() -> AuthzConfiguration.operations(List.of("get")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("tesk.api.authz.operations must include create");
        assertThatThrownBy(() -> AuthzConfiguration.operations(List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("tesk.api.authz.operations must include create");
    }

    @ParameterizedTest
    @ValueSource(strings = {"delete", "", "GET", "create get"})
    public void operations_unknown_failsAtStartup(String name) {
        assertThatThrownBy(() -> AuthzConfiguration.operations(List.of("create", name)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("tesk.api.authz.operations has an unknown operation");
    }

    @Test
    public void operations_null_failsAtStartup() {
        assertThatThrownBy(() -> AuthzConfiguration.operations(Arrays.asList("create", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("tesk.api.authz.operations has an unknown operation ''");
    }

    @Test
    public void operations_repeated_failsAtStartup() {
        assertThatThrownBy(() -> AuthzConfiguration.operations(List.of("create", "get", "get")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("tesk.api.authz.operations names 'get' more than once");
    }

    @Test
    public void failure_openAndClosed() {
        Map<String, String> failure = new LinkedHashMap<>();
        failure.put("create", "closed");
        failure.put("get", " open ");
        failure.put("list", "closed");
        failure.put("cancel", "open");
        assertThat(AuthzConfiguration.failOpen(failure, EnumSet.allOf(Operation.class)))
                .containsExactlyInAnyOrder(Operation.GET, Operation.CANCEL);
    }

    /** Open for an operation that isn't switched on changes nothing (a warning) */
    @Test
    public void failure_openForAnOperationNotSwitchedOn() {
        assertThat(
                        AuthzConfiguration.failOpen(
                                Map.of("list", "open"), EnumSet.of(Operation.CREATE)))
                .containsExactly(Operation.LIST);
    }

    @Test
    public void failure_createOpen_failsAtStartup() {
        assertThatThrownBy(() ->
                        AuthzConfiguration.failOpen(
                                Map.of("create", "open"), EnumSet.allOf(Operation.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("tesk.api.authz.failure.create can't be open");
    }

    @Test
    public void failure_unknownOperation_failsAtStartup() {
        assertThatThrownBy(() ->
                        AuthzConfiguration.failOpen(
                                Map.of("delete", "open"), EnumSet.allOf(Operation.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("tesk.api.authz.failure.delete names an unknown operation");
    }

    @Test
    public void failure_unknownValue_failsAtStartup() {
        Map<String, String> failure = new LinkedHashMap<>();
        failure.put("get", null);
        assertThatThrownBy(
                        () -> AuthzConfiguration.failOpen(failure, EnumSet.allOf(Operation.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("tesk.api.authz.failure.get must be closed or open");
        assertThatThrownBy(() ->
                        AuthzConfiguration.failOpen(
                                Map.of("get", "allow"), EnumSet.allOf(Operation.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("tesk.api.authz.failure.get must be closed or open");
    }

    /** The bean of get, list and cancel: the switched-on operations, with the service info's ID */
    @Test
    @SuppressWarnings("unchecked")
    public void taskAccess() throws Exception {
        AuthzConfiguration configuration = new AuthzConfiguration();
        ServiceInfoService serviceInfo = mock(ServiceInfoService.class);
        TesServiceInfo info = new TesServiceInfo();
        info.setId("org.example.tes");
        when(serviceInfo.getServiceInfo()).thenReturn(info);
        DecisionServiceClient decisionService = mock(DecisionServiceClient.class);
        when(decisionService.answer(any(), anyString()))
                .thenThrow(new DecisionServiceClient.Failure("down"));
        this.properties.setOperations(new ArrayList<>(List.of("create", "get", "list")));
        this.properties.setFailure(new LinkedHashMap<>(Map.of("list", "open")));

        TaskAccess access = configuration.taskAccess(decisionService, this.properties, serviceInfo);

        assertThat(access.decides(Operation.GET)).isTrue();
        assertThat(access.decides(Operation.CANCEL)).isFalse();
        TokenPrincipal alice =
                new TokenPrincipal(
                        "https://issuer.example/",
                        "alice",
                        TokenPrincipal.Kind.USER,
                        null,
                        Map.of());
        User user = User.builder(alice.owner()).principal(alice).build();
        assertThat(access.listScope(user, TaskView.MINIMAL)).isEqualTo(TaskAccess.ListScope.OWN);
        assertThatThrownBy(() -> access.mayGet(user, "t", null, TaskView.MINIMAL))
                .isInstanceOf(AuthorisationUnavailableException.class);
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(decisionService, org.mockito.Mockito.times(2))
                .answer(request.capture(), anyString());
        Map<String, Object> context = (Map<String, Object>) request.getValue().get("context");
        assertThat((Map<String, Object>) context.get("tes"))
                .containsEntry("service_id", "org.example.tes");
    }

    @Test
    public void taskAccess_invalidSettings_failsAtStartup() {
        this.properties.setOperations(List.of("get"));
        assertThatThrownBy(() ->
                        new AuthzConfiguration()
                                .taskAccess(
                                        mock(DecisionServiceClient.class),
                                        this.properties,
                                        mock(ServiceInfoService.class)))
                .isInstanceOf(IllegalStateException.class);
    }
}
