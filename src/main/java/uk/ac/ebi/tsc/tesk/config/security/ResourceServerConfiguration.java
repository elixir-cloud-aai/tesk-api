package uk.ac.ebi.tsc.tesk.config.security;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.boot.web.servlet.error.ErrorAttributes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.core.GrantedAuthorityDefaults;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

/**
 * With the auth profile, protects (all but the public) endpoints with OAuth2 bearer tokens, using
 * the resource server of Spring Security: tokens are validated at the user info endpoint of the
 * authorisation server ({@code security.oauth2.resource.user-info-uri}; {@link
 * UserInfoTokenIntrospector}), or, with tesk.api.authorisation.service-clients enabled, the tokens
 * of the listed service clients locally ({@link ServiceClientTokenServices}).
 *
 * @author Ania Niewielska <aniewielska@ebi.ac.uk>
 */
@Configuration
@EnableGlobalMethodSecurity(prePostEnabled = true)
@Profile("auth")
public class ResourceServerConfiguration {

    // The API description, and the Swagger UI paths, which are only mapped (else 404) with
    // tesk.api.swagger-ui.enabled
    private static final String[] ALLOWED_URLS = {
        "/",
        "/v2/api-docs",
        "/configuration/ui",
        "/swagger-resources/**",
        "/configuration/**",
        "/swagger-ui/**",
        "/webjars/**"
    };

    private final ErrorAttributes errorAttributes;
    private final HttpMessageConverters messageConverters;
    private final AuthorisationProperties authorisationProperties;
    private final String baseUrl;
    private final boolean serviceInfoPublic;
    private final String userInfoUri;

    public ResourceServerConfiguration(
            ErrorAttributes errorAttributes,
            HttpMessageConverters messageConverters,
            AuthorisationProperties authorisationProperties,
            @Value("${openapi.taskExecutionService.base-path:/ga4gh/tes/v1}") String baseUrl,
            @Value("${tesk.api.service-info.public:true}") boolean serviceInfoPublic,
            @Value("${security.oauth2.resource.user-info-uri:}") String userInfoUri) {

        this.errorAttributes = errorAttributes;
        this.messageConverters = messageConverters;
        this.authorisationProperties = authorisationProperties;
        this.baseUrl = baseUrl;
        this.serviceInfoPublic = serviceInfoPublic;
        this.userInfoUri = userInfoUri;
    }

    /**
     * Lets only the Swagger UI requests in without a token, and service info unless a token is
     * required for it (tesk.api.service-info.public). A request with a token has it validated also
     * on these. Requests without a valid token get the JSON error responses and {@code
     * WWW-Authenticate} headers of {@link CustomAuthenticationEntryPoint}. Stateless and without
     * CSRF protection (no cookies are used), as the resource server of Spring Security OAuth was.
     */
    @Bean
    public SecurityFilterChain resourceServerFilterChain(
            HttpSecurity http, OpaqueTokenIntrospector introspector) throws Exception {
        List<String> allowedUrls = new ArrayList<>(Arrays.asList(ALLOWED_URLS));
        if (serviceInfoPublic) {
            allowedUrls.add(baseUrl + "/service-info");
        }
        String[] allowedUrlsArray = allowedUrls.toArray(new String[0]);
        AuthenticationEntryPoint entryPoint =
                new CustomAuthenticationEntryPoint(errorAttributes, messageConverters);
        http.csrf()
                .disable()
                .sessionManagement()
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                .and()
                .authorizeRequests()
                .antMatchers(HttpMethod.GET, allowedUrlsArray)
                .permitAll()
                .antMatchers(HttpMethod.HEAD, allowedUrlsArray)
                .permitAll()
                .anyRequest()
                .authenticated()
                .and()
                .exceptionHandling()
                .authenticationEntryPoint(entryPoint)
                .and()
                .oauth2ResourceServer()
                .authenticationEntryPoint(entryPoint)
                .opaqueToken()
                .introspector(introspector)
                .authenticationConverter(AuthenticatedUser::toAuthentication);
        return http.build();
    }

    /**
     * Validates the tokens at the user info endpoint, and, with tesk.api.authorisation.service-
     * clients enabled, the tokens of the listed service clients locally ({@link
     * ServiceClientTokenServices})
     */
    @Bean
    public OpaqueTokenIntrospector tokenIntrospector() {
        GroupNamesSecurityExtractor authoritiesExtractor =
                new GroupNamesSecurityExtractor(
                        authorisationProperties.getGroupsClaim(),
                        authorisationProperties.getGroupPrefix());
        OpaqueTokenIntrospector userInfoIntrospector =
                new UserInfoTokenIntrospector(
                        userInfoUri,
                        new ElixirPrincipalExtractor(authoritiesExtractor, authorisationProperties),
                        authoritiesExtractor);
        if (authorisationProperties.getServiceClients().isEnabled()) {
            return new ServiceClientTokenServices(userInfoIntrospector, authorisationProperties);
        }
        return userInfoIntrospector;
    }

    /** Magic, that changes default role prefix from ROLE_ to (...)elixir: */
    @Bean
    public GrantedAuthorityDefaults grantedAuthorityDefaults() {
        return new GrantedAuthorityDefaults(this.authorisationProperties.getGroupPrefix());
    }

    /**
     * Switches off default in memory user store containing one user with auto-generated password
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return username -> null;
    }
}
