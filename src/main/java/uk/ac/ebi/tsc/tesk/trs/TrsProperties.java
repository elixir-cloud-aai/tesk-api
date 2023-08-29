package uk.ac.ebi.tsc.tesk.trs;

import lombok.Data;
import lombok.Getter;

import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @author aniewielska
 * @since 22/09/2020
 */
@Configuration
@ConfigurationProperties(prefix = "tesk.api.trs")
@Data
public class TrsProperties {
    @Getter private String uriPattern;
    @Getter private String urlPattern;
}

