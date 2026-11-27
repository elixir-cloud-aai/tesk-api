package uk.ac.ebi.tsc.tesk.config.security;

import jakarta.servlet.DispatcherType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.http.converter.autoconfigure.ServerHttpMessageConvertersCustomizer;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import uk.ac.ebi.tsc.tesk.config.security.token.TokenValidationProperties;
import uk.ac.ebi.tsc.tesk.config.security.token.TokenValidator;
import uk.ac.ebi.tsc.tesk.config.security.token.TokenValidators;
import uk.ac.ebi.tsc.tesk.config.swagger.ApiDocsConfiguration;

/**
 * With the auth profile (the default), protects (all but the public) endpoints with OAuth2 bearer
 * tokens, using the resource server of Spring Security: every token is validated locally by the
 * {@link TokenValidator} that {@code tesk.api.auth.token-validator} selects ({@link
 * TokenValidatorIntrospector}), and the caller is identified as {@code <iss>|<sub>}, for users and
 * service clients alike. Settings that are incomplete or invalid stop TESK API at startup.
 *
 * @author Ania Niewielska &lt;aniewielska@ebi.ac.uk&gt;
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(TokenValidationProperties.class)
@Profile("auth")
public class ResourceServerConfiguration {

    // The API description (/v3/api-docs, also as YAML), and the Swagger UI paths, which are only
    // mapped (else 404) with tesk.api.swagger-ui.enabled
    private static final String[] ALLOWED_URLS = {
        "/",
        ApiDocsConfiguration.API_DOCS,
        ApiDocsConfiguration.API_DOCS + "/**",
        ApiDocsConfiguration.API_DOCS + ".yaml",
        "/swagger-ui/**",
        "/webjars/**"
    };

    /**
     * Service clients listed under no issuer, as Spring names the setting from properties ({@code
     * tesk.api.auth.service-clients}, {@code serviceClients}) and environment variables ({@code
     * TESK_API_AUTH_SERVICE_CLIENTS_*}, {@code TESK_API_AUTH_SERVICECLIENTS_*}), dashes removed
     */
    static final List<String> MISPLACED =
            List.of("tesk.api.auth.serviceclients", "tesk.api.auth.service.clients");

    private final ErrorAttributes errorAttributes;
    private final ObjectProvider<ServerHttpMessageConvertersCustomizer>
            messageConvertersCustomizers;
    private final String baseUrl;
    private final boolean serviceInfoPublic;

    public ResourceServerConfiguration(
            ErrorAttributes errorAttributes,
            ObjectProvider<ServerHttpMessageConvertersCustomizer> messageConvertersCustomizers,
            @Value("${openapi.taskExecutionService.base-path:/ga4gh/tes/v1}") String baseUrl,
            @Value("${tesk.api.service-info.public:true}") boolean serviceInfoPublic) {

        this.errorAttributes = errorAttributes;
        this.messageConvertersCustomizers = messageConvertersCustomizers;
        this.baseUrl = baseUrl;
        this.serviceInfoPublic = serviceInfoPublic;
    }

    /**
     * Lets only the Swagger UI requests in without a token, and service info unless a token is
     * required for it (tesk.api.service-info.public). On these public paths, a token is ignored,
     * valid or not ({@link #bearerTokenResolver}). Requests without a valid token get the JSON
     * error responses of {@link CustomAuthenticationEntryPoint}: {@code 401} with {@code
     * WWW-Authenticate}, or {@code 503} if the token can't be checked now. Stateless and without
     * CSRF protection, as no cookies are used. With allowed origins of CORS, Spring Security adds
     * the CORS filter of {@link CorsConfig} before authentication, so that preflight requests need
     * no token.
     */
    @Bean
    public SecurityFilterChain resourceServerFilterChain(
            HttpSecurity http, OpaqueTokenIntrospector introspector) throws Exception {
        String[] allowedUrls = allowedUrls();
        AuthenticationEntryPoint entryPoint =
                new CustomAuthenticationEntryPoint(
                        errorAttributes, serverMessageConverters(messageConvertersCustomizers));
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests ->
                        // Error dispatches (to /error) aren't authorised again, as before
                        requests.dispatcherTypeMatchers(DispatcherType.ERROR)
                                .permitAll()
                                .requestMatchers(HttpMethod.GET, allowedUrls)
                                .permitAll()
                                .requestMatchers(HttpMethod.HEAD, allowedUrls)
                                .permitAll()
                                .anyRequest()
                                .authenticated())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint))
                .oauth2ResourceServer(resourceServer ->
                        resourceServer
                                .authenticationEntryPoint(entryPoint)
                                .bearerTokenResolver(bearerTokenResolver(publicPaths(allowedUrls)))
                                .opaqueToken(opaqueToken ->
                                        opaqueToken
                                                .introspector(introspector)
                                                .authenticationConverter(
                                                        AuthenticatedUser::toAuthentication)));
        return http.build();
    }

    /** The paths that need no token (GET and HEAD) */
    String[] allowedUrls() {
        List<String> allowedUrls = new ArrayList<>(Arrays.asList(ALLOWED_URLS));
        if (serviceInfoPublic) {
            allowedUrls.add(baseUrl + "/service-info");
        }
        return allowedUrls.toArray(new String[0]);
    }

    /** GET and HEAD requests to the paths */
    static RequestMatcher publicPaths(String[] paths) {
        List<RequestMatcher> matchers = new ArrayList<>();
        for (String path : paths) {
            matchers.add(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, path));
            matchers.add(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.HEAD, path));
        }
        return new OrRequestMatcher(matchers);
    }

    /**
     * Takes the token from the {@code Authorization} header only (not from the URL or a form), and
     * not at all on the public paths: there a token, valid or not, is ignored, so that clients that
     * send their token everywhere aren't refused service info, and public requests never make TESK
     * API ask an issuer for its keys
     */
    static BearerTokenResolver bearerTokenResolver(RequestMatcher publicPaths) {
        DefaultBearerTokenResolver header = new DefaultBearerTokenResolver();
        return request -> publicPaths.matches(request) ? null : header.resolve(request);
    }

    /**
     * Validates the tokens with the configured validator
     *
     * @throws IllegalStateException if the settings are incomplete or invalid (e.g. no issuer, an
     *     issuer without audiences or service clients, plain http URLs except to the local host,
     *     service clients listed under no issuer), naming the setting
     */
    @Bean
    public TokenValidator tokenValidator(
            TokenValidationProperties properties, Environment environment) {
        refuseMisplacedSettings(environment);
        return TokenValidators.create(properties);
    }

    @Bean
    public OpaqueTokenIntrospector tokenIntrospector(TokenValidator tokenValidator) {
        return new TokenValidatorIntrospector(tokenValidator);
    }

    /**
     * Refuses service clients listed under no issuer ({@link #MISPLACED}), which would otherwise be
     * ignored
     *
     * @throws IllegalStateException if one is set, naming it and where it belongs
     */
    static void refuseMisplacedSettings(Environment environment) {
        Optional<ConfigurationPropertyName> misplaced =
                PropertyNames.firstSet(environment, ResourceServerConfiguration::isMisplaced);
        if (misplaced.isPresent()) {
            throw new IllegalStateException(
                    misplaced.get()
                            + ": service clients are listed per issuer, as"
                            + " tesk.api.auth.issuers[i].service-clients[j].client-id"
                            + " (see the configuration reference)");
        }
    }

    /** Whether the name is a setting of {@link #MISPLACED} */
    static boolean isMisplaced(ConfigurationPropertyName name) {
        return PropertyNames.below(name, MISPLACED).isPresent();
    }

    /**
     * The message converters of Spring MVC, as Spring Boot configures them (e.g. Jackson with the
     * {@code spring.jackson.*} settings), for the error responses written outside of Spring MVC
     */
    static HttpMessageConverters serverMessageConverters(
            ObjectProvider<ServerHttpMessageConvertersCustomizer> customizers) {
        HttpMessageConverters.ServerBuilder builder =
                HttpMessageConverters.forServer().registerDefaults();
        customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
        return builder.build();
    }

    /**
     * Switches off default in memory user store containing one user with auto-generated password
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return username -> null;
    }
}
