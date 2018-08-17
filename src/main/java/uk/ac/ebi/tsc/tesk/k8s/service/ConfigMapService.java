package uk.ac.ebi.tsc.tesk.k8s.service;

import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.models.V1ConfigMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import uk.ac.ebi.tsc.tesk.common.exception.TaskNotFoundException;
import uk.ac.ebi.tsc.tesk.k8s.exception.KubernetesException;

/**
 * @author aniewielska
 * @since 17/08/2018
 */
@Service
public class ConfigMapService {


    private final CoreV1Api coreApi;

    private final CoreV1Api patchCoreApi;


    private final String namespace;

    public ConfigMapService(CoreV1Api coreApi, @Qualifier("patchCoreApi") CoreV1Api patchCoreApi, @Value("${tesk.api.k8s.namespace}") String namespace) {
        this.coreApi = coreApi;
        this.patchCoreApi = patchCoreApi;
        this.namespace = namespace;
    }


    public V1ConfigMap createConfigMap(V1ConfigMap configMap) {
        try {
            return this.coreApi.createNamespacedConfigMap(namespace, configMap, null);
        } catch (ApiException e) {
            throw KubernetesException.fromApiException(e);
        }
    }

    public V1ConfigMap patchConfigMap(V1ConfigMap configMap) {
        try {
            return this.patchCoreApi.patchNamespacedConfigMap(configMap.getMetadata().getName(), namespace, configMap, null);
        } catch (ApiException e) {
            throw KubernetesException.fromApiException(e);
        }
    }

    public V1ConfigMap getConfigMap(String name) {
        try {
            return this.coreApi.readNamespacedConfigMap(name, namespace, null, null, null);
        } catch (ApiException e) {
            if (e.getCode() != HttpStatus.NOT_FOUND.value())
                throw KubernetesException.fromApiException(e);
        }
        throw new TaskNotFoundException(name);
    }
}
