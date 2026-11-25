package uk.ac.ebi.tsc.tesk.accessmatrix;

import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.Config;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import uk.ac.ebi.tsc.tesk.accessmatrix.Callers.Caller;
import uk.ac.ebi.tsc.tesk.accessmatrix.Matrix.Operation;
import uk.ac.ebi.tsc.tesk.accessmatrix.MockIdp.Token;

/**
 * The harness of the access matrix: TESK with the auth profile, a mock OpenID Connect provider
 * ({@link MockIdp}), a mock decision service ({@link MockPdp}) and a mock Kubernetes API ({@link
 * MockCluster}); requests per operation, and the check of a cell against its target or its pending
 * entry ({@link Pending}).
 *
 * <p>The settings hold both today's (group model, user info as authentication, service clients of
 * #220, which the slices of the redesign remove) and the redesign's (design section 7: {@code
 * tesk.api.auth.*}, {@code tesk.api.authz.*}, which have no effect until the slices use them), so
 * that the same suite runs before, during and after the redesign. Each slice removes the settings
 * it deletes. Today's group settings are those of the deployments with LS Login and the base group
 * {@code elixir:ECP_CLN}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        locations = {"classpath:application.properties"},
        properties = {
            "spring.profiles.active=auth",
            // today: user info as authentication, groups of LS Login, service clients (#220)
            "security.oauth2.resource.user-info-uri=" + MockIdp.USER_INFO_URI,
            "tesk.api.authorisation.group-prefix=" + Entitlements.PREFIX,
            "tesk.api.authorisation.group-suffix=" + Entitlements.SUFFIX,
            "tesk.api.authorisation.base-group=" + Entitlements.BASE_GROUP,
            "tesk.api.authorisation.service-clients.enabled=true",
            "tesk.api.authorisation.service-clients.issuer=" + MockIdp.ISSUER,
            "tesk.api.authorisation.service-clients.clients={\""
                    + MockIdp.LISTED_CLIENT
                    + "\": {\"audiences\": [\""
                    + MockIdp.LISTED_CLIENT
                    + "\"]}}",
            // the redesign (design section 7), without effect until its slices
            "tesk.api.auth.issuers[0].url=" + MockIdp.ISSUER,
            "tesk.api.auth.issuers[0].audiences=" + MockIdp.FRONT_END,
            "tesk.api.auth.issuers[0].service-clients[0].client-id=" + MockIdp.LISTED_CLIENT,
            "tesk.api.auth.issuers[0].trusted-actors=" + MockIdp.TRUSTED_ACTOR,
            "tesk.api.auth.timeout-seconds=1",
            "tesk.api.authz.decision-service.url=" + MockPdp.URL,
            "tesk.api.authz.decision-service.timeout=1"
        })
abstract class Harness {

    static final String OWN = "task-own";
    static final String FOREIGN = "task-foreign";
    static final String MISSING = "task-missing";

    static final String TASKS = "/ga4gh/tes/v1/tasks";
    static final String SERVICE_INFO = "/ga4gh/tes/v1/service-info";

    /** A task without a group (TES 1.1 minimal) */
    static final String TASK =
            "{\"executors\": [{\"image\": \"ubuntu\", \"command\": [\"echo\", \"hello\"]}]}";

    static final MockPdp PDP = new MockPdp();

    @RegisterExtension
    static WireMockExtension idp =
            WireMockExtension.newInstance()
                    .options(wireMockConfig().port(MockIdp.PORT))
                    .failOnUnmatchedRequests(true)
                    .build();

    @RegisterExtension
    static WireMockExtension pdp =
            WireMockExtension.newInstance()
                    .options(wireMockConfig().port(MockPdp.PORT).extensions(PDP))
                    .failOnUnmatchedRequests(true)
                    .build();

    @RegisterExtension
    static WireMockExtension kubernetes =
            WireMockExtension.newInstance()
                    .options(wireMockConfig().port(MockCluster.PORT))
                    .failOnUnmatchedRequests(true)
                    .build();

    /** The client of the mock Kubernetes API; each test class nests a test configuration of it */
    static class KubernetesClientMock {
        @Bean
        @Primary
        public ApiClient kubernetesApiClient() {
            return Config.fromUrl("http://localhost:" + MockCluster.PORT, false);
        }
    }

    @Autowired MockMvc mvc;

    /** The configuration's name in {@code pending.csv} */
    abstract String config();

    @BeforeEach
    void stubServices() {
        MockIdp.stub(idp);
        PDP.reset();
        pdp.givenThat(post(MockPdp.PATH).willReturn(ok()));
    }

    /**
     * Prepares the caller: its token (null without one), with the answers of the user info endpoint
     * and the decision service's mode
     */
    String prepare(Caller caller) {
        Token token = caller.token().get();
        if (token == null) {
            return null;
        }
        if (caller.userInfoDelayMs() > 0) {
            MockIdp.stubSlowUserInfo(idp, token, caller.userInfoDelayMs());
        } else {
            MockIdp.stubUserInfo(idp, token);
        }
        PDP.mode(caller.pdp());
        return token.value();
    }

    /** Performs the operation (on the task {@code taskId}, for get and cancel) */
    MvcResult perform(Operation operation, String token, String taskId) throws Exception {
        MockHttpServletRequestBuilder request =
                switch (operation) {
                    case SI -> get(SERVICE_INFO);
                    case CREATE ->
                            MockMvcRequestBuilders.post(TASKS)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(TASK);
                    case GET_MINIMAL -> get(TASKS + "/{id}?view=MINIMAL", taskId);
                    case GET_BASIC -> get(TASKS + "/{id}?view=BASIC", taskId);
                    case GET_FULL -> get(TASKS + "/{id}?view=FULL", taskId);
                    case LIST -> get(TASKS + "?view=MINIMAL");
                    case CANCEL ->
                            MockMvcRequestBuilders.post(TASKS + "/{id}:cancel", taskId)
                                    .contentType(MediaType.APPLICATION_JSON);
                };
        request.accept(MediaType.APPLICATION_JSON);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return this.mvc.perform(request).andReturn();
    }

    /** An assertion of a response */
    @FunctionalInterface
    interface Target {
        void check(MvcResult result) throws Exception;
    }

    /**
     * Checks the response of a cell against its target, unless the cell is pending: then the
     * response must still have today's status and miss the target, and the test ends as aborted
     */
    void check(String caseId, String operation, MvcResult result, String expected, Target target)
            throws Exception {
        Optional<Pending.Entry> pending = Pending.find(config(), caseId, operation);
        if (pending.isPresent() && Pending.DECIDE.equals(pending.get().slice())) {
            Assumptions.abort("needs a decision first; target as proposed: " + expected);
        }
        AssertionError missed = null;
        try {
            target.check(result);
        } catch (AssertionError e) {
            missed = e;
        }
        int status = result.getResponse().getStatus();
        if (pending.isEmpty()) {
            if (missed != null) {
                throw missed;
            }
            return;
        }
        Pending.Entry entry = pending.get();
        if (missed == null) {
            fail(
                    "Meets its target ("
                            + expected
                            + ") now: remove the line "
                            + String.join(",", config(), caseId, operation)
                            + " (slice "
                            + entry.slice()
                            + ") from "
                            + Pending.RESOURCE);
        }
        assertEquals(
                entry.today(),
                status,
                "Pending until "
                        + entry.slice()
                        + ", but no longer answers today's status: update "
                        + Pending.RESOURCE
                        + " (target "
                        + expected
                        + ")");
        Assumptions.abort(
                "pending until "
                        + entry.slice()
                        + ": answers "
                        + status
                        + ", target "
                        + expected
                        + " ("
                        + missed.getMessage().lines().findFirst().orElse("")
                        + ")");
    }

    /**
     * The target of a cell of the matrix ({@code 200}, {@code 200[]}, {@code 200o}, {@code 4xx})
     */
    static Target cell(String expected, Operation operation, String taskId) {
        return result -> {
            int status = result.getResponse().getStatus();
            switch (expected) {
                case "200" -> {
                    assertEquals(200, status, "status");
                    if (operation == Operation.GET_MINIMAL
                            || operation == Operation.GET_BASIC
                            || operation == Operation.GET_FULL) {
                        assertEquals(taskId, json(result).get("id").getAsString(), "task");
                    } else if (operation == Operation.CREATE) {
                        assertEquals(
                                MockCluster.CREATED, json(result).get("id").getAsString(), "task");
                    }
                }
                case "200[]" -> {
                    assertEquals(200, status, "status");
                    assertEquals(List.of(), listed(result), "tasks");
                }
                case "200o" -> {
                    assertEquals(200, status, "status");
                    assertEquals(List.of(OWN), listed(result), "tasks");
                }
                case "401" -> {
                    assertEquals(401, status, "status");
                    String challenge = result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE);
                    assertNotNull(challenge, "WWW-Authenticate");
                    assertTrue(challenge.startsWith("Bearer"), "WWW-Authenticate: " + challenge);
                }
                default -> assertEquals(Integer.parseInt(expected), status, "status");
            }
        };
    }

    static JsonObject json(MvcResult result) throws Exception {
        return JsonParser.parseString(result.getResponse().getContentAsString()).getAsJsonObject();
    }

    /** The IDs of the listed tasks, in order */
    static List<String> listed(MvcResult result) throws Exception {
        List<String> ids = new ArrayList<>();
        JsonElement tasks = json(result).get("tasks");
        if (tasks != null && !tasks.isJsonNull()) {
            JsonArray array = tasks.getAsJsonArray();
            for (JsonElement task : array) {
                ids.add(task.getAsJsonObject().get("id").getAsString());
            }
        }
        return ids;
    }
}
