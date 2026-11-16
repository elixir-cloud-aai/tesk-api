package uk.ac.ebi.tsc.tesk.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.kubernetes.client.openapi.ApiException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.logging.Log;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.PointcutAdvisor;
import uk.ac.ebi.tsc.tesk.config.TraceInterceptorConfiguration.KubernetesAPICallsInterceptor;
import uk.ac.ebi.tsc.tesk.k8s.exception.KubernetesException;
import uk.ac.ebi.tsc.tesk.k8s.service.KubernetesClientWrapper;

/** Which calls to Kubernetes are traced, and what the trace of a failed call holds */
public class TraceInterceptorConfigurationTest {

    private final TraceInterceptorConfiguration configuration = new TraceInterceptorConfiguration();

    /** The writing calls are traced with their results, but never the creation of Secrets */
    @Test
    public void writeAdvisor_matchesCreateAndLabelCallsButNotSecrets() {
        assertThat(matched(configuration.writeTaskTraceAdvisor()))
                .containsExactlyInAnyOrder(
                        "createJob",
                        "createConfigMap",
                        "labelJobAsCancelled",
                        "labelPodAsCancelled");
    }

    /** The reading calls are traced with their arguments */
    @Test
    public void getAdvisor_matchesListAndReadCalls() {
        assertThat(matched(configuration.getTaskTraceAdvisor()))
                .containsExactlyInAnyOrder(
                        "readTaskmasterJob",
                        "readPodLog",
                        "listAllTaskmasterJobsForUser",
                        "listTaskmasterJobsOfCreator",
                        "listSingleTaskExecutorJobs",
                        "listAllTaskExecutorJobs",
                        "listFilerJobsOfTasks",
                        "listUnlabelledFilerJobs",
                        "listSingleJobPods",
                        "listAllJobPods");
    }

    /** A failed Kubernetes call adds the response body of Kubernetes to the trace */
    @Test
    public void writeToLog_addsResponseBodyOfKubernetesErrors() {
        Log log = mock(Log.class);
        when(log.isTraceEnabled()).thenReturn(true);
        KubernetesException ex =
                KubernetesException.fromApiException(
                        new ApiException(422, null, "{\"reason\":\"Invalid\"}"));

        new KubernetesAPICallsInterceptor().writeToLog(log, "END: createJob thrown", ex);

        verify(log).trace("END: createJob thrown", ex);
        verify(log).trace("ApiException ResponseBody: {\"reason\":\"Invalid\"}");
    }

    /** Other exceptions have no response body to add */
    @Test
    public void writeToLog_otherExceptionsOnly() {
        Log log = mock(Log.class);
        RuntimeException ex = new RuntimeException("boom");

        new KubernetesAPICallsInterceptor().writeToLog(log, "END: readPodLog thrown", ex);

        verify(log).trace("END: readPodLog thrown", ex);
        verify(log, never()).trace(anyString());
    }

    private static List<String> matched(Advisor advisor) {
        PointcutAdvisor pointcutAdvisor = (PointcutAdvisor) advisor;
        return Arrays.stream(KubernetesClientWrapper.class.getDeclaredMethods())
                .filter(method -> matches(pointcutAdvisor, method))
                .map(Method::getName)
                .collect(Collectors.toList());
    }

    private static boolean matches(PointcutAdvisor advisor, Method method) {
        return advisor.getPointcut().getClassFilter().matches(KubernetesClientWrapper.class)
                && advisor.getPointcut()
                        .getMethodMatcher()
                        .matches(method, KubernetesClientWrapper.class);
    }
}
