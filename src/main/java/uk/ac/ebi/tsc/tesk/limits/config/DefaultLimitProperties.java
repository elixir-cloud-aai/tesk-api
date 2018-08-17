package uk.ac.ebi.tsc.tesk.limits.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import uk.ac.ebi.tsc.tesk.limits.data.ResourceUsage;

/**
 * @author aniewielska
 * @since 17/08/2018
 */
@Configuration
@ConfigurationProperties(prefix = "tesk.quota")
@Data
public class DefaultLimitProperties {
    private ResourceUsage resourceUsage;
}
