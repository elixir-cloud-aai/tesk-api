package uk.ac.ebi.tsc.tesk.config.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.util.InvalidMimeTypeException;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.util.WebUtils;

/**
 * Answers requests without a valid access token with {@code 401 Unauthorized}, in our usual error
 * format (Spring Boot's error attributes, written with the autoconfigured message converters) plus
 * the OAuth 2.0 error code ({@code error}) and description ({@code error_description}), and a
 * {@code WWW-Authenticate} header with both (RFC 6750, section 3). These are the answers of the
 * resource server of the former Spring Security OAuth: {@code unauthorized} without a token, {@code
 * invalid_token} for a token that isn't valid. A token that can't be checked now (e.g. the keys of
 * its issuer can't be fetched, {@link TokenValidatorIntrospector.TokenCheckUnavailableException})
 * gets {@code 503 Service Unavailable} with the error {@code temporarily_unavailable}, in the same
 * format, without {@code WWW-Authenticate}: the client did nothing wrong and may retry.
 *
 * @author Ania Niewielska &lt;aniewielska@ebi.ac.uk&gt;
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

    /** The error code if the token can't be checked now */
    static final String TEMPORARILY_UNAVAILABLE = "temporarily_unavailable";

    /** Name of the OAuth 2.0 error description (RFC 6750, section 3) */
    static final String DESCRIPTION = "error_description";

    private final ErrorAttributes errorAttributes;
    private final List<HttpMessageConverter<?>> messageConverters;

    CustomAuthenticationEntryPoint(
            ErrorAttributes errorAttributes, HttpMessageConverters messageConverters) {
        this.errorAttributes = errorAttributes;
        this.messageConverters = new ArrayList<>();
        messageConverters.forEach(this.messageConverters::add);
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException)
            throws IOException {
        if (authException instanceof TokenValidatorIntrospector.TokenCheckUnavailableException) {
            HttpStatus status = HttpStatus.SERVICE_UNAVAILABLE;
            Map<String, Object> body =
                    errorBody(
                            request,
                            authException,
                            status,
                            TEMPORARILY_UNAVAILABLE,
                            authException.getMessage());
            response.setStatus(status.value());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            write(body, request, response);
            response.flushBuffer();
            return;
        }
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
        Map<String, Object> body =
                errorBody(request, authException, HttpStatus.UNAUTHORIZED, error, description);

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, wwwAuthenticate(error, description));
        write(body, request, response);
        response.flushBuffer();
    }

    /**
     * Spring Boot's error attributes for the status, with the message, plus the OAuth 2.0 error
     * code and description
     */
    private Map<String, Object> errorBody(
            HttpServletRequest request,
            AuthenticationException authException,
            HttpStatus status,
            String error,
            String description) {
        WebUtils.exposeErrorRequestAttributes(request, authException, null);
        request.setAttribute(WebUtils.ERROR_STATUS_CODE_ATTRIBUTE, status.value());
        Map<String, Object> body =
                this.errorAttributes.getErrorAttributes(
                        new ServletWebRequest(request),
                        ErrorAttributeOptions.defaults()
                                .including(ErrorAttributeOptions.Include.MESSAGE));
        body.put(ERROR, error);
        body.put(DESCRIPTION, description);
        return body;
    }

    /** The {@code WWW-Authenticate} header, with quotes and backslashes in the values escaped */
    public static String wwwAuthenticate(String error, String description) {
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
        // q=0: not acceptable
        accepted.removeIf(mediaType -> mediaType.getQualityValue() == 0);
        try {
            MimeTypeUtils.sortBySpecificity(accepted);
        } catch (InvalidMimeTypeException e) {
            // more than 50 media types, which Spring refuses to sort: answer as without Accept
            accepted = new ArrayList<>(Collections.singletonList(MediaType.ALL));
        }
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
