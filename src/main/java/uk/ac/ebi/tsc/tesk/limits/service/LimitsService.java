package uk.ac.ebi.tsc.tesk.limits.service;

import io.kubernetes.client.models.V1ConfigMap;
import org.springframework.stereotype.Service;
import uk.ac.ebi.tsc.tesk.common.exception.TaskNotFoundException;
import uk.ac.ebi.tsc.tesk.k8s.exception.KubernetesException;
import uk.ac.ebi.tsc.tesk.k8s.service.ConfigMapService;
import uk.ac.ebi.tsc.tesk.limits.config.DefaultLimitProperties;
import uk.ac.ebi.tsc.tesk.limits.convert.K8sLimitsConverter;
import uk.ac.ebi.tsc.tesk.limits.data.GroupResourceUsage;
import uk.ac.ebi.tsc.tesk.limits.data.LimitType;
import uk.ac.ebi.tsc.tesk.limits.data.ResourceUsage;

/**
 * @author aniewielska
 * @since 17/08/2018
 */
@Service
public class LimitsService {

    private final K8sLimitsConverter converter;
    private final DefaultLimitProperties defaults;
    private final ConfigMapService k8sService;

    public LimitsService(K8sLimitsConverter converter, DefaultLimitProperties defaults, ConfigMapService k8sService) {
        this.converter = converter;
        this.defaults = defaults;
        this.k8sService = k8sService;
    }

    public GroupResourceUsage createDefaultLimitForGroup(String groupName, LimitType type) {
        GroupResourceUsage limit = GroupResourceUsage.builder().name(groupName).type(type).resourceUsage(defaults.getResourceUsage()).build();
        V1ConfigMap configMap = this.converter.fromResourceLimitToConfigMap(limit);
        V1ConfigMap result;
        try {
            result = this.k8sService.createConfigMap(configMap);
        } catch (KubernetesException ex) {
            if (ex.isObjectNameDuplicated()) {
                result = this.k8sService.patchConfigMap(configMap);
            } else {
                throw ex;
            }
        }
        return this.converter.fromConfigMapToResourceLimit(groupName, type, result);
    }

    public GroupResourceUsage getLimitForGroup(String groupName, LimitType type) {
        GroupResourceUsage limit = GroupResourceUsage.builder().name(groupName).type(type).resourceUsage(new ResourceUsage()).build();
        V1ConfigMap emptyConfigMap = this.converter.fromResourceLimitToConfigMap(limit);
        V1ConfigMap result;
        try {
            result = this.k8sService.getConfigMap(emptyConfigMap.getMetadata().getName());
            return this.converter.fromConfigMapToResourceLimit(groupName, type, result);
        } catch (TaskNotFoundException ex) {
            return this.createDefaultLimitForGroup(groupName, type);
        }
    }
}
