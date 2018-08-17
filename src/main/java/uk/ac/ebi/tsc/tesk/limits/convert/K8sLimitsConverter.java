package uk.ac.ebi.tsc.tesk.limits.convert;

import io.kubernetes.client.models.V1ConfigMap;
import io.kubernetes.client.models.V1ObjectMeta;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.PropertyAccessorFactory;
import org.springframework.stereotype.Service;
import uk.ac.ebi.tsc.tesk.common.exception.TaskNotFoundException;
import uk.ac.ebi.tsc.tesk.limits.data.GroupResourceUsage;
import uk.ac.ebi.tsc.tesk.limits.data.LimitType;
import uk.ac.ebi.tsc.tesk.limits.data.ResourceUsage;

import java.beans.PropertyDescriptor;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author aniewielska
 * @since 17/08/2018
 */
@Service
@Slf4j
public class K8sLimitsConverter {

    private static final String GROUP_CONFIG_MAP_NAME = "group-limit-config";
    private static final String USER_CONFIG_MAP_NAME = "user-limit-config";


    public GroupResourceUsage fromConfigMapToResourceLimit(String name, LimitType type, V1ConfigMap configMap) {
        Map<String, String> data = configMap.getData();
        Map<String, String> dataForGroup = data.entrySet().stream().filter(entry -> entry.getKey().startsWith(getDataPropertyPrefix(name, type))).
                collect(Collectors.toMap(entry -> entry.getKey().replaceFirst(getDataPropertyPrefix(name, type), ""), entry -> entry.getValue()));
        if (dataForGroup.isEmpty()) {
            //TODO -> add separate exception classes
            throw new TaskNotFoundException(name);
        }
        ResourceUsage resourceUsage = new ResourceUsage();
        BeanWrapper wrapper = PropertyAccessorFactory.forBeanPropertyAccess(resourceUsage);
        wrapper.setPropertyValues(dataForGroup);
        return GroupResourceUsage.builder().name(name).type(type).resourceUsage(resourceUsage).build();

    }

    public V1ConfigMap fromResourceLimitToConfigMap(GroupResourceUsage limit) {
        V1ConfigMap configMap = new V1ConfigMap();

        BeanWrapper wrapper = PropertyAccessorFactory.forBeanPropertyAccess(limit.getResourceUsage());

        for (PropertyDescriptor property : wrapper.getPropertyDescriptors()) {
            if (property.getPropertyType() != Class.class) {
                Object value = wrapper.getPropertyValue(property.getName());
                if (value != null) {
                    configMap.putDataItem(getDataPropertyPrefix(limit.getName(), limit.getType()) + property.getName(), value.toString());
                }
            }

        }
        configMap.metadata(new V1ObjectMeta().name(limit.getType() == LimitType.GROUP ? GROUP_CONFIG_MAP_NAME : USER_CONFIG_MAP_NAME));
        return configMap;
    }

    private String getDataPropertyPrefix(String name, LimitType type) {
        return type.name() + "." + name + ".";
    }
}
