package uk.ac.ebi.tsc.tesk.config;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.TypeMismatchException;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import uk.ac.ebi.tsc.tesk.config.security.CustomAuthenticationEntryPoint;
import uk.ac.ebi.tsc.tesk.tes.exception.AuthorisationUnavailableException;
import uk.ac.ebi.tsc.tesk.tes.exception.UserInfoRefusedException;

/**
 * Custom Error Handler:
 *
 * <ol>
 *   <li>created mainly to bypass default Spring Boot error controller {@link BasicErrorController}
 *       in order to avoid generating html error page for browser requests (always tries to return
 *       JSON instead)
 *   <li>uses default Spring Boot's ErrorAttributes, customizing output for
 *       MethodArgumentNotValidException
 *   <li>overrides ResponseEntityExceptionHandler {@link ResponseEntityExceptionHandler} to return
 *       sensible HTTP status codes for Spring internal exceptions
 * </ol>
 *
 * @author Ania Niewielska &lt;aniewielska@ebi.ac.uk&gt;
 */
@ControllerAdvice
public class CustomErrorHandler extends ResponseEntityExceptionHandler {

    private final ErrorAttributes errorAttributes;

    public CustomErrorHandler(ErrorAttributes errorAttributes) {
        this.errorAttributes = errorAttributes;
    }

    /** Access denied handler */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseBody
    public ResponseEntity<?> accessDeniedHandler(
            HttpServletRequest request, AccessDeniedException ex) {
        return this.handleExceptionInternal(request, HttpStatus.FORBIDDEN);
    }

    /**
     * The user info endpoint refused the caller's token when a task was created: {@code 401}, as
     * for other invalid tokens ({@link CustomAuthenticationEntryPoint}), with {@code
     * WWW-Authenticate} ({@code invalid_token}) and the OAuth 2.0 error in the body
     */
    @ExceptionHandler(UserInfoRefusedException.class)
    @ResponseBody
    public ResponseEntity<?> userInfoRefusedHandler(
            HttpServletRequest request, UserInfoRefusedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(
                        HttpHeaders.WWW_AUTHENTICATE,
                        CustomAuthenticationEntryPoint.wwwAuthenticate(
                                OAuth2ErrorCodes.INVALID_TOKEN, ex.getMessage()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(
                        this.oauthErrorBody(
                                request,
                                HttpStatus.UNAUTHORIZED,
                                OAuth2ErrorCodes.INVALID_TOKEN,
                                ex.getMessage()));
    }

    /**
     * The right to create a task can't be decided now: {@code 503}, as for tokens that can't be
     * checked now ({@link CustomAuthenticationEntryPoint}), with {@code Cache-Control: no-store}
     * and the OAuth 2.0 error {@code temporarily_unavailable} in the body
     */
    @ExceptionHandler(AuthorisationUnavailableException.class)
    @ResponseBody
    public ResponseEntity<?> authorisationUnavailableHandler(
            HttpServletRequest request, AuthorisationUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(
                        this.oauthErrorBody(
                                request,
                                HttpStatus.SERVICE_UNAVAILABLE,
                                OAuth2ErrorCodes.TEMPORARILY_UNAVAILABLE,
                                ex.getMessage()));
    }

    /** The error attributes for the status, plus the OAuth 2.0 error code and description */
    @SuppressWarnings("unchecked")
    private Map<String, Object> oauthErrorBody(
            HttpServletRequest request, HttpStatus status, String error, String description) {
        Map<String, Object> body =
                (Map<String, Object>) this.handleExceptionInternal(request, status).getBody();
        body.put("error", error);
        body.put("error_description", description);
        return body;
    }

    /**
     * Fallback handler for all exceptions other than those handled in
     * ResponseEntityExceptionHandler Tries to retrieve response status from Exception annotation.
     * If not present, returns HTTP 500. Places default ErrorAttributes in the response body
     */
    @ExceptionHandler(Exception.class)
    @ResponseBody
    public ResponseEntity<?> commonErrorHandler(HttpServletRequest request, Exception ex) {
        ResponseStatus requestedResponseStatus =
                AnnotatedElementUtils.findMergedAnnotation(ex.getClass(), ResponseStatus.class);
        HttpStatus status =
                requestedResponseStatus != null
                        ? requestedResponseStatus.code()
                        : HttpStatus.INTERNAL_SERVER_ERROR;
        return this.handleExceptionInternal(request, status);
    }

    /**
     * Handles MethodArgumentNotValidException, replacing message and error from Spring Boot's
     * default implementation with messages composed of validator binding results.
     */
    @Override
    @SuppressWarnings("unchecked")
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ResponseEntity<Object> response =
                super.handleMethodArgumentNotValid(ex, headers, status, request);
        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors();
        List<ObjectError> globalErrors = ex.getBindingResult().getGlobalErrors();
        List<String> errors = new ArrayList<>(fieldErrors.size() + globalErrors.size());
        String error;
        for (FieldError fieldError : fieldErrors) {
            error = "Field '" + fieldError.getField() + "' " + fieldError.getDefaultMessage();
            errors.add(error);
        }
        for (ObjectError objectError : globalErrors) {
            error = objectError.getObjectName() + " " + objectError.getDefaultMessage();
            errors.add(error);
        }
        Map<String, Object> responseBody = (Map<String, Object>) response.getBody();
        responseBody.remove("message");
        responseBody.remove("errors");
        responseBody.put("messages", errors);
        return response;
    }

    /**
     * Handles request parameters of the wrong type (e.g. {@code page_size=abc}) with a message that
     * names the parameter, but not the value, instead of Spring's message, which repeats the value.
     */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        if (ex instanceof MethodArgumentTypeMismatchException) {
            request.setAttribute(
                    RequestDispatcher.ERROR_MESSAGE,
                    "Invalid value of parameter "
                            + ((MethodArgumentTypeMismatchException) ex).getName()
                            + ".",
                    RequestAttributes.SCOPE_REQUEST);
        }
        return super.handleTypeMismatch(ex, headers, status, request);
    }

    /**
     * Customizes the response body of all Spring Boot default exception types adding map retrieved
     * from default errorAttributes
     */
    @Override
    @SuppressWarnings("unchecked")
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        // Spring MVC passes the requests of its exception handlers as ServletWebRequests
        HttpServletRequest httpServletRequest = ((ServletWebRequest) request).getRequest();
        return (ResponseEntity<Object>) this.handleExceptionInternal(httpServletRequest, status);
    }

    /**
     * Retrieves (from default errorAttributes object) the JSON object to be placed by default in
     * response body.
     */
    private ResponseEntity<?> handleExceptionInternal(
            HttpServletRequest request, HttpStatusCode status) {
        WebRequest requestAttributes = new ServletWebRequest(request);
        // missing request parameters used by DefaultErrorAttributes implementation
        requestAttributes.setAttribute(
                "jakarta.servlet.error.status_code",
                status.value(),
                RequestAttributes.SCOPE_REQUEST);
        requestAttributes.setAttribute(
                "jakarta.servlet.error.request_uri",
                request.getRequestURI(),
                RequestAttributes.SCOPE_REQUEST);
        return new ResponseEntity<>(
                this.errorAttributes.getErrorAttributes(
                        requestAttributes,
                        ErrorAttributeOptions.defaults()
                                .including(ErrorAttributeOptions.Include.MESSAGE)),
                status);
    }
}
