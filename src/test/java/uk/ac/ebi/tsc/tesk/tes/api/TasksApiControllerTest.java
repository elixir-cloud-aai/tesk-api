package uk.ac.ebi.tsc.tesk.tes.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.ac.ebi.tsc.tesk.TestUtils.getFileContentFromResources;
import static uk.ac.ebi.tsc.tesk.UrlConstants.TASK_URL;

import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.common.OAuth2AccessToken;
import org.springframework.security.oauth2.common.exceptions.InvalidTokenException;
import org.springframework.security.oauth2.config.annotation.web.configuration.EnableResourceServer;
import org.springframework.security.oauth2.config.annotation.web.configuration.ResourceServerConfigurerAdapter;
import org.springframework.security.oauth2.config.annotation.web.configurers.ResourceServerSecurityConfigurer;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.security.oauth2.provider.OAuth2Request;
import org.springframework.security.oauth2.provider.token.ResourceServerTokenServices;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import uk.ac.ebi.tsc.tesk.config.security.User;
import uk.ac.ebi.tsc.tesk.tes.exception.InvalidPageTokenException;
import uk.ac.ebi.tsc.tesk.tes.exception.TaskNotFoundException;
import uk.ac.ebi.tsc.tesk.tes.model.TaskView;
import uk.ac.ebi.tsc.tesk.tes.model.TesListTasksResponse;
import uk.ac.ebi.tsc.tesk.tes.model.TesTask;
import uk.ac.ebi.tsc.tesk.tes.service.ServiceInfoService;
import uk.ac.ebi.tsc.tesk.tes.service.TesService;

@RunWith(SpringRunner.class)
@WebMvcTest(TasksApiController.class)
public class TasksApiControllerTest {

    @TestConfiguration
    @EnableResourceServer
    static class ResourceServerConfiguration extends ResourceServerConfigurerAdapter {

        @Override
        public void configure(ResourceServerSecurityConfigurer security) {
            security.tokenServices(tokenServices());
        }

        ResourceServerTokenServices tokenServices() {
            return new ResourceServerTokenServices() {

                @Override
                public OAuth2Authentication loadAuthentication(String accessToken)
                        throws AuthenticationException, InvalidTokenException {
                    Object principal = User.builder("admin").build();
                    List<GrantedAuthority> authorities =
                            AuthorityUtils.commaSeparatedStringToAuthorityList(accessToken);
                    OAuth2Request request =
                            new OAuth2Request(null, null, null, true, null, null, null, null, null);
                    UsernamePasswordAuthenticationToken token =
                            new UsernamePasswordAuthenticationToken(principal, "N/A", authorities);
                    return new OAuth2Authentication(request, token);
                }

                @Override
                public OAuth2AccessToken readAccessToken(String accessToken) {
                    return null;
                }
            };
        }
    }

    @Autowired private MockMvc mvc;

    @MockBean private TesService tesService;
    @MockBean ServiceInfoService serviceInfoService;

    @Test
    public void createTask_valid() throws Exception {
        String[] paths = new String[] {"fromTesToK8s/task.json", "fromTesToK8s_minimal/task.json"};
        for (String path : paths) {
            this.mvc
                    .perform(
                            post(TASK_URL)
                                    .content(getFileContentFromResources(path))
                                    .header("Authorization", "Bearer BAR")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());
        }
    }

    @Test
    public void createTask_privateImageCredentialsMustBeComplete() throws Exception {
        String task =
                "{\"executors\":[{\"image\":\"registry.example.org/image\","
                        + "\"command\":[\"true\"],\"docker_registry\":\"registry.example.org\","
                        + "\"docker_login\":\"robot\"}]}";
        this.mvc
                .perform(
                        post(TASK_URL)
                                .header("Authorization", "Bearer BAR")
                                .content(task)
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(
                        content()
                                .string(
                                        containsString(
                                                "docker_registry, docker_login and docker_pass")));
        verify(this.tesService, never()).createTask(any(), any());
    }

    @Test
    public void createTask_rejectsCredentialsEmbeddedInRegistryAddress() throws Exception {
        String task =
                "{\"executors\":[{\"image\":\"registry.example.org/image\","
                        + "\"command\":[\"true\"],"
                        + "\"docker_registry\":\"https://robot:embedded-token@registry.example.org\","
                        + "\"docker_login\":\"robot\",\"docker_pass\":\"private-token\"}]}";
        this.mvc
                .perform(
                        post(TASK_URL)
                                .header("Authorization", "Bearer BAR")
                                .content(task)
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("embedded credentials")))
                .andExpect(content().string(not(containsString("embedded-token"))))
                .andExpect(content().string(not(containsString("private-token"))));
        verify(this.tesService, never()).createTask(any(), any());
    }

    @Test
    public void createTask_rejectsWhitespaceInRegistryAddress() throws Exception {
        String task =
                "{\"executors\":[{\"image\":\"registry.example.org/image\","
                        + "\"command\":[\"true\"],"
                        + "\"docker_registry\":\"registry.example.org invalid\","
                        + "\"docker_login\":\"robot\",\"docker_pass\":\"private-token\"}]}";
        this.mvc
                .perform(
                        post(TASK_URL)
                                .header("Authorization", "Bearer BAR")
                                .content(task)
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("docker_registry")))
                .andExpect(content().string(not(containsString("private-token"))));
        verify(this.tesService, never()).createTask(any(), any());
    }

    @Test
    public void createTask_acceptsPrivateImageCredentials() throws Exception {
        String task =
                "{\"executors\":[{\"image\":\"registry.example.org/image\","
                        + "\"command\":[\"true\"],\"docker_registry\":\"registry.example.org\","
                        + "\"docker_login\":\"robot\",\"docker_pass\":\"token\"}]}";
        this.mvc
                .perform(
                        post(TASK_URL)
                                .header("Authorization", "Bearer BAR")
                                .content(task)
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        verify(this.tesService)
                .createTask(
                        argThat(
                                (TesTask created) ->
                                        "token"
                                                .equals(
                                                        created.getExecutors()
                                                                .get(0)
                                                                .getDockerPass())),
                        any());
    }

    @Test
    public void createTask_invalid_executors() throws Exception {
        String[][] files =
                new String[][] {
                    new String[] {"none.json", "executors"},
                    new String[] {"empty.json", "executors"},
                    new String[] {"no_image.json", "image"},
                    new String[] {"no_command.json", "command"},
                    new String[] {"invalid_stdin.json", "stdin"},
                    new String[] {"ignore_error_true.json", "ignore_error: true is not supported"},
                };
        for (String[] file : files) {
            this.mvc
                    .perform(
                            post(TASK_URL)
                                    .header("Authorization", "Bearer BAR")
                                    .content(
                                            getFileContentFromResources(
                                                    "invalid_tasks/executors/" + file[0]))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().is4xxClientError())
                    .andExpect(jsonPath("$.messages[0]", containsString(file[1])));
        }
    }

    @Test
    public void createTask_invalid_inputs() throws Exception {
        String[][] files =
                new String[][] {
                    new String[] {"no_path.json", "inputs[0].path"},
                    new String[] {"no_source.json", "URL or content required"},
                    new String[] {"wrong_type.json", "inputs[0].type"}
                };
        for (String[] file : files) {
            this.mvc
                    .perform(
                            post(TASK_URL)
                                    .header("Authorization", "Bearer BAR")
                                    .content(
                                            getFileContentFromResources(
                                                    "invalid_tasks/inputs/" + file[0]))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().is4xxClientError())
                    .andExpect(jsonPath("$.messages[0]", containsString(file[1])));
        }
    }

    @Test
    public void createTask_invalid_outputs() throws Exception {
        String[][] files =
                new String[][] {
                    new String[] {"no_path.json", "outputs[0].path"},
                    new String[] {"no_url.json", "outputs[0].url"},
                    new String[] {"empty_type.json", "outputs[0].type"},
                    new String[] {"relative_path_prefix.json", "outputs[0].pathPrefix"},
                    new String[] {
                        "wildcard_in_first_component.json", "none in its first component"
                    },
                    new String[] {
                        "path_prefix_below_pattern.json", "part of path before its first wildcard"
                    }
                };
        for (String[] file : files) {
            this.mvc
                    .perform(
                            post(TASK_URL)
                                    .header("Authorization", "Bearer BAR")
                                    .content(
                                            getFileContentFromResources(
                                                    "invalid_tasks/outputs/" + file[0]))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().is4xxClientError())
                    .andExpect(jsonPath("$.messages[0]", containsString(file[1])));
        }
    }

    @Test
    public void createTask_invalid_volumes() throws Exception {
        String[][] files = new String[][] {new String[] {"wrong_volumes.json", "volumes[1]"}};
        for (String[] file : files) {
            this.mvc
                    .perform(
                            post(TASK_URL)
                                    .header("Authorization", "Bearer BAR")
                                    .content(
                                            getFileContentFromResources("invalid_tasks/" + file[0]))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().is4xxClientError())
                    .andExpect(jsonPath("$.messages[0]", containsString(file[1])));
        }
    }

    @Test
    public void createTask_invalid_unrecognized_field() throws Exception {
        String[][] files =
                new String[][] {
                    new String[] {"unrecognized_field.json", "Unrecognized field \"unknown\""}
                };
        for (String[] file : files) {
            this.mvc
                    .perform(
                            post(TASK_URL)
                                    .header("Authorization", "Bearer BAR")
                                    .content(
                                            getFileContentFromResources("invalid_tasks/" + file[0]))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().is4xxClientError())
                    .andExpect(jsonPath("$.message", containsString(file[1])));
        }
    }

    /**
     * The TES specification answers a cancellation with an empty object ({@code {}}), not an empty
     * body (issue #37)
     */
    @Test
    public void cancelTask_returnsEmptyObject() throws Exception {
        this.mvc
                .perform(
                        post(TASK_URL + "/{id}:cancel", "task-123")
                                .header("Authorization", "Bearer BAR")
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{}", true));
        // the task is looked up for the user (authorisation) before it is cancelled
        InOrder order = inOrder(this.tesService);
        order.verify(this.tesService)
                .getTask(eq("task-123"), eq(TaskView.MINIMAL), argThat(isAdmin()));
        order.verify(this.tesService).cancelTask("task-123");
    }

    @Test
    public void cancelTask_notFound_doesNotCancel() throws Exception {
        given(this.tesService.getTask(eq("task-123"), eq(TaskView.MINIMAL), argThat(isAdmin())))
                .willThrow(new TaskNotFoundException("task-123"));
        this.mvc
                .perform(
                        post(TASK_URL + "/{id}:cancel", "task-123")
                                .header("Authorization", "Bearer BAR")
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
        verify(this.tesService, never()).cancelTask(anyString());
    }

    /** Matches the user of the test's token (see {@link ResourceServerConfiguration}) */
    private static ArgumentMatcher<User> isAdmin() {
        return user -> user != null && "admin".equals(user.getUsername());
    }

    /**
     * CPU cores are limited to 6 integer digits and 3 decimals (steps of 1m in Kubernetes), so that
     * huge or tiny numbers (e.g. 1e1000000000) are refused instead of converted (issue #44)
     */
    @Test
    public void createTask_invalid_cpuCores() throws Exception {
        String[] files =
                new String[] {
                    "cpu_cores_1e1000000000.json",
                    "cpu_cores_1e_10.json",
                    "cpu_cores_0_0005.json",
                    "cpu_cores_1000000.json",
                    "cpu_cores_1e2147483647.json"
                };
        for (String file : files) {
            // refused by the validation (messages) or, beyond the range of BigDecimal's scale, when
            // read (message)
            this.mvc
                    .perform(
                            post(TASK_URL)
                                    .header("Authorization", "Bearer BAR")
                                    .content(
                                            getFileContentFromResources(
                                                    "invalid_tasks/resources/" + file))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(containsString("cpu")));
        }
    }

    /**
     * CPU cores, RAM and disk must be positive: zero and negative numbers are refused before
     * Kubernetes is asked, with the field and the bound in the message; RAM needs at least 0.000001
     * GB, the smallest amount that TESK API requests from Kubernetes (issue #128)
     */
    @Test
    public void createTask_invalid_nonPositiveResources() throws Exception {
        String[][] cases =
                new String[][] {
                    {"cpu_cores_0.json", "Field 'resources.cpuCores' must be greater than 0"},
                    {"cpu_cores_minus_1.json", "Field 'resources.cpuCores' must be greater than 0"},
                    {
                        "ram_gb_0.json",
                        "Field 'resources.ramGb' must be greater than or equal to 0.000001"
                    },
                    {
                        "ram_gb_minus_1.json",
                        "Field 'resources.ramGb' must be greater than or equal to 0.000001"
                    },
                    {
                        "ram_gb_1e_7.json",
                        "Field 'resources.ramGb' must be greater than or equal to 0.000001"
                    },
                    {"disk_gb_0.json", "Field 'resources.diskGb' must be greater than 0"},
                    {"disk_gb_minus_5.json", "Field 'resources.diskGb' must be greater than 0"}
                };
        for (String[] c : cases) {
            try {
                this.mvc
                        .perform(
                                post(TASK_URL)
                                        .header("Authorization", "Bearer BAR")
                                        .content(
                                                getFileContentFromResources(
                                                        "invalid_tasks/resources/" + c[0]))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .accept(MediaType.APPLICATION_JSON))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.messages[0]").value(c[1]));
            } catch (AssertionError e) {
                throw new AssertionError(c[0] + ": " + e.getMessage(), e);
            }
        }
        verify(this.tesService, never()).createTask(any(), any());
    }

    /** The smallest amount of RAM that TESK API requests from Kubernetes is accepted (#128) */
    @Test
    public void createTask_valid_smallestRam() throws Exception {
        this.mvc
                .perform(
                        post(TASK_URL)
                                .header("Authorization", "Bearer BAR")
                                .content(
                                        getFileContentFromResources(
                                                "valid_tasks/ram_gb_0_000001.json"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    /**
     * The fields that TES 1.1 added are accepted: outputs with wildcards and path_prefix,
     * streamable inputs, ignore_error false and backend parameters that aren't strict
     */
    @Test
    public void createTask_valid_tes11Fields() throws Exception {
        this.mvc
                .perform(
                        post(TASK_URL)
                                .header("Authorization", "Bearer BAR")
                                .content(
                                        getFileContentFromResources(
                                                "valid_tasks/tes_1_1_fields.json"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    /** Strict backend parameters are refused: TESK uses none */
    @Test
    public void createTask_invalid_strictBackendParameters() throws Exception {
        this.mvc
                .perform(
                        post(TASK_URL)
                                .header("Authorization", "Bearer BAR")
                                .content(
                                        getFileContentFromResources(
                                                "invalid_tasks/resources/backend_parameters_strict.json"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().is4xxClientError())
                .andExpect(
                        jsonPath(
                                "$.messages[0]",
                                containsString("backend_parameters are not supported")));
    }

    /** Trailing zeros don't count as decimals */
    @Test
    public void createTask_valid_cpuCoresTrailingZeros() throws Exception {
        this.mvc
                .perform(
                        post(TASK_URL)
                                .header("Authorization", "Bearer BAR")
                                .content(
                                        getFileContentFromResources(
                                                "valid_tasks/cpu_cores_0_5000.json"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    /**
     * page_size must be between 1 and 2048; other numbers, also those that would wrap around when
     * converted to an int, are refused before Kubernetes is asked (issue #114)
     */
    @Test
    public void listTasks_invalidPageSize() throws Exception {
        String[] pageSizes = new String[] {"0", "-1", "2049", "4294967297", "9223372036854775807"};
        for (String pageSize : pageSizes) {
            this.mvc
                    .perform(
                            get(TASK_URL)
                                    .param("page_size", pageSize)
                                    .header("Authorization", "Bearer BAR")
                                    .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("page_size")))
                    .andExpect(jsonPath("$.message", containsString("2048")));
        }
        verify(this.tesService, never()).listTasks(any(), any(), any(), any(), any());
    }

    /**
     * Values that aren't whole numbers (or don't fit a long) are refused, too, with a message that
     * names the parameter, not the value (issue #114)
     */
    @Test
    public void listTasks_nonNumericPageSize() throws Exception {
        String[] pageSizes = new String[] {"abc", "1.5", "99999999999999999999"};
        for (String pageSize : pageSizes) {
            this.mvc
                    .perform(
                            get(TASK_URL)
                                    .param("page_size", pageSize)
                                    .header("Authorization", "Bearer BAR")
                                    .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("page_size")))
                    .andExpect(jsonPath("$.message", not(containsString(pageSize))));
        }
        verify(this.tesService, never()).listTasks(any(), any(), any(), any(), any());
    }

    /** The limits themselves are accepted (issue #114) */
    @Test
    public void listTasks_validPageSize() throws Exception {
        given(this.tesService.listTasks(any(), any(), any(), any(), any()))
                .willReturn(new TesListTasksResponse());
        for (long pageSize : new long[] {1L, TasksApiController.MAX_PAGE_SIZE}) {
            this.mvc
                    .perform(
                            get(TASK_URL)
                                    .param("page_size", String.valueOf(pageSize))
                                    .header("Authorization", "Bearer BAR")
                                    .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());
            verify(this.tesService)
                    .listTasks(isNull(), eq(pageSize), isNull(), eq(TaskView.MINIMAL), any());
        }
    }

    /**
     * An expired or malformed page_token is a client error: 400 with a message that names the
     * parameter, never its value (issue #114)
     */
    @Test
    public void listTasks_invalidPageToken() throws Exception {
        given(this.tesService.listTasks(any(), any(), eq("secret-token"), any(), any()))
                .willThrow(new InvalidPageTokenException());
        this.mvc
                .perform(
                        get(TASK_URL)
                                .param("page_token", "secret-token")
                                .header("Authorization", "Bearer BAR")
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("page_token")))
                .andExpect(content().string(not(containsString("secret-token"))));
    }
}
