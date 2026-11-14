package uk.ac.ebi.tsc.tesk.config.security;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.ErrorAttributes;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.util.WebUtils;

/**
 * Answers requests without a valid access token with {@code 401 Unauthorized}, in our usual error
 * format (Spring Boot's error attributes, written with the autoconfigured message converters) plus
 * the OAuth 2.0 error code ({@code error}) and description ({@code error_description}), and a
 * {@code WWW-Authenticate} header with both (RFC 6750, section 3). These are the answers of the
 * resource server of the former Spring Security OAuth: {@code unauthorized} without a token, {@code
 * invalid_token} for a token that isn't valid.
 *
 * @author Ania Niewielska <aniewielska@ebi.ac.uk>
 */
public class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger logger =
            LoggerFactory.getLogger(CustomAuthenticationEntryPoint.class);

    /** The realm in {@code WWW-Authenticate}, as before */
    static final String REALM = "oauth";

    /** The error code without an OAuth 2.0 error, e.g. without a token */
    static final String UNAUTHORIZED = "unauthorized";

    /** Name of the OAuth 2.0 error code (RFC 6750, section 3) */
    static final String ERROR = "error";

    /** Name of the OAuth 2.0 error description (RFC 6750, section 3) */
    static final String DESCRIPTION = "error_description";

    private final ErrorAttributes errorAttributes;
    private final List<HttpMessageConverter<?>> messageConverters;

    CustomAuthenticationEntryPoint(
            ErrorAttributes errorAttributes, HttpMessageConverters messageConverters) {
        this.errorAttributes = errorAttributes;
        this.messageConverters = new ArrayList<>(messageConverters.getConverters());
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException)
            throws IOException {
        String error = UNAUTHORIZED;
        String description = authException.getMessage();
        if (authException instanceof OAuth2AuthenticationException) {
            OAuth2Error oauth2Error = ((OAuth2AuthenticationException) authException).getError();
            error = oauth2Error.getErrorCode();
            if (StringUtils.hasText(oauth2Error.getDescription())) {
                description = oauth2Error.getDescription();
            }
        }
        if (!StringUtils.hasText(description)) {
            description = HttpStatus.UNAUTHORIZED.getReasonPhrase();
        }
        WebUtils.exposeErrorRequestAttributes(request, authException, null);
        request.setAttribute(WebUtils.ERROR_STATUS_CODE_ATTRIBUTE, HttpStatus.UNAUTHORIZED.value());
        Map<String, Object> body =
                this.errorAttributes.getErrorAttributes(
                        new ServletWebRequest(request),
                        ErrorAttributeOptions.defaults()
                                .including(ErrorAttributeOptions.Include.MESSAGE));
        body.put(ERROR, error);
        body.put(DESCRIPTION, description);

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, wwwAuthenticate(error, description));
        write(body, request, response);
        response.flushBuffer();
    }

    /** The {@code WWW-Authenticate} header, with quotes and backslashes in the values escaped */
    static String wwwAuthenticate(String error, String description) {
        return String.format(
                "Bearer realm=\"%s\", %s=\"%s\", %s=\"%s\"",
                REALM, ERROR, quote(error), DESCRIPTION, quote(description));
    }

    private static String quote(String value) {
        return String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Writes the body with the first converter that can write it in a media type that the request
     * accepts (most specific first), as before; without one, the answer has no body
     */
    @SuppressWarnings("unchecked")
    private void write(
            Map<String, Object> body, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        List<MediaType> accepted;
        try {
            accepted = new ArrayList<>(MediaType.parseMediaTypes(request.getHeader("Accept")));
        } catch (IllegalArgumentException e) {
            accepted = new ArrayList<>();
        }
        if (accepted.isEmpty()) {
            accepted.add(MediaType.ALL);
        }
        MediaType.sortBySpecificityAndQuality(accepted);
        for (MediaType mediaType : accepted) {
            for (HttpMessageConverter<?> converter : this.messageConverters) {
                if (converter.canWrite(body.getClass(), mediaType)) {
                    ((HttpMessageConverter<Object>) converter)
                            .write(body, mediaType, new ServletServerHttpResponse(response));
                    return;
                }
            }
        }
        logger.debug("No message converter writes the error body in an accepted media type");
    }
}
