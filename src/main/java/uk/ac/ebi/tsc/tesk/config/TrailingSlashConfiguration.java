package uk.ac.ebi.tsc.tesk.config;

import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.UrlHandlerFilter;

/**
 * Ignores a trailing slash in request paths (e.g. {@code /ga4gh/tes/v1/tasks/} is {@code
 * /ga4gh/tes/v1/tasks}), as Spring Framework 5 did, which TESK API used until 2.0.0: Spring
 * Framework 6 and later no longer match it. After Spring Security, so that it checks the paths as
 * before (e.g. {@code /service-info/} isn't public, as before).
 */
@Configuration
public class TrailingSlashConfiguration {

    @Bean
    public FilterRegistrationBean<UrlHandlerFilter> trailingSlashFilter() {
        FilterRegistrationBean<UrlHandlerFilter> registration =
                new FilterRegistrationBean<>(
                        UrlHandlerFilter.trailingSlashHandler("/**").wrapRequest().build());
        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 1);
        return registration;
    }
}
