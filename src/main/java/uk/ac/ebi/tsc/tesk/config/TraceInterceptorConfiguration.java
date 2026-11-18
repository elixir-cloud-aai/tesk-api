package uk.ac.ebi.tsc.tesk.config;

import org.apache.commons.logging.Log;
import org.springframework.aop.Advisor;
import org.springframework.aop.aspectj.AspectJExpressionPointcut;
import org.springframework.aop.interceptor.CustomizableTraceInterceptor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import uk.ac.ebi.tsc.tesk.k8s.exception.KubernetesException;

/**
 * Traces the calls of {@link uk.ac.ebi.tsc.tesk.k8s.service.KubernetesClientWrapper} to Kubernetes
 * at level TRACE: arguments and results of the writing calls (except {@code createSecret}, whose
 * argument holds credentials), and arguments of the reading calls
 *
 * @author Ania Niewielska &lt;aniewielska@ebi.ac.uk&gt;
 */
@Configuration
@EnableAspectJAutoProxy(proxyTargetClass = true)
public class TraceInterceptorConfiguration {

    private static final String WRAPPER =
            "public * uk.ac.ebi.tsc.tesk.k8s.service.KubernetesClientWrapper+";

    /** Creating (except Secrets) and labelling calls */
    static final String WRITE_METHODS =
            "(execution("
                    + WRAPPER
                    + ".create*(..)) && !execution("
                    + WRAPPER
                    + ".createSecret(..))) || execution("
                    + WRAPPER
                    + ".label*(..))";

    /** Listing and reading calls */
    static final String GET_METHODS =
            "execution(" + WRAPPER + ".list*(..)) || execution(" + WRAPPER + ".read*(..))";

    /** Adds the response body of a failed Kubernetes call to the trace of the exception */
    static class KubernetesAPICallsInterceptor extends CustomizableTraceInterceptor {
        @Override
        protected void writeToLog(Log logger, String message, Throwable ex) {
            super.writeToLog(logger, message, ex);
            if (ex instanceof KubernetesException) {
                KubernetesException exception = (KubernetesException) ex;
                logger.trace(
                        "ApiException ResponseBody: "
                                + exception.getApiException().getResponseBody());
            }
        }
    }

    @Bean
    public CustomizableTraceInterceptor writeMethodsInterceptor() {
        CustomizableTraceInterceptor customizableTraceInterceptor =
                new KubernetesAPICallsInterceptor();
        customizableTraceInterceptor.setUseDynamicLogger(true);
        customizableTraceInterceptor.setEnterMessage(
                "START: $[methodName], ARGUMENTS: $[arguments]");
        customizableTraceInterceptor.setExitMessage(
                "END:  $[methodName](), RESULT: $[returnValue], TIME: $[invocationTime]");
        customizableTraceInterceptor.setExceptionMessage("END: $[methodName] thrown $[exception]");
        customizableTraceInterceptor.setLogExceptionStackTrace(false);
        return customizableTraceInterceptor;
    }

    @Bean
    public CustomizableTraceInterceptor getMethodsInterceptor() {
        CustomizableTraceInterceptor customizableTraceInterceptor =
                new KubernetesAPICallsInterceptor();
        customizableTraceInterceptor.setUseDynamicLogger(true);
        customizableTraceInterceptor.setEnterMessage(
                "START: $[methodName], ARGUMENTS $[arguments]");
        customizableTraceInterceptor.setExitMessage("END:  $[methodName], TIME: $[invocationTime]");
        customizableTraceInterceptor.setExceptionMessage("END: $[methodName] thrown $[exception]");
        customizableTraceInterceptor.setLogExceptionStackTrace(false);
        return customizableTraceInterceptor;
    }

    @Bean
    public Advisor writeTaskTraceAdvisor() {
        AspectJExpressionPointcut pointcut = new AspectJExpressionPointcut();
        pointcut.setExpression(WRITE_METHODS);
        return new DefaultPointcutAdvisor(pointcut, writeMethodsInterceptor());
    }

    @Bean
    public Advisor getTaskTraceAdvisor() {
        AspectJExpressionPointcut pointcut = new AspectJExpressionPointcut();
        pointcut.setExpression(GET_METHODS);
        return new DefaultPointcutAdvisor(pointcut, getMethodsInterceptor());
    }
}
