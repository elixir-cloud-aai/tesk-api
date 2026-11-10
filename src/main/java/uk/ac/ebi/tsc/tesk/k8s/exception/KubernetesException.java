package uk.ac.ebi.tsc.tesk.k8s.exception;

import io.kubernetes.client.openapi.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * General wrapper for exception thrown by Kubernetes API
 *
 * @author Ania Niewielska <aniewielska@ebi.ac.uk>
 */
@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
public class KubernetesException extends RuntimeException {

    private ApiException apiException;

    public static KubernetesException fromApiException(ApiException apiException) {
        return new KubernetesException(apiException);
    }

    /** Avoids logging a Secret admission error that may echo the submitted credentials. */
    public static KubernetesException fromSecretApiException(ApiException apiException) {
        return new KubernetesException(
                apiException,
                "Kubernetes rejected an image pull Secret (HTTP " + apiException.getCode() + ")");
    }

    private KubernetesException(ApiException apiException) {
        super(apiException.getMessage(), apiException.getCause());
        this.apiException = apiException;
    }

    private KubernetesException(ApiException apiException, String safeMessage) {
        super(safeMessage);
        this.apiException = apiException;
    }

    public ApiException getApiException() {
        return this.apiException;
    }

    public boolean isObjectNameDuplicated() {
        return apiException.getCode() == HttpStatus.CONFLICT.value();
    }
}
