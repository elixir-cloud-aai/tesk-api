package uk.ac.ebi.tsc.tesk.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import jakarta.servlet.RequestDispatcher;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.TypeMismatchException;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;
import uk.ac.ebi.tsc.tesk.tes.exception.TaskNotFoundException;
import uk.ac.ebi.tsc.tesk.tes.exception.UserInfoRefusedException;

/** Messages of {@link CustomErrorHandler} for request parameters of the wrong type (issue #114) */
public class CustomErrorHandlerTest {

    /**
     * Type mismatches that aren't about a named method argument keep Spring's message: there is no
     * parameter name to put in its place
     */
    @Test
    public void handleTypeMismatch_withoutParameterName() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/tasks");
        DefaultErrorAttributes errorAttributes = new DefaultErrorAttributes();
        CustomErrorHandler handler = new CustomErrorHandler(errorAttributes);
        TypeMismatchException ex = new TypeMismatchException("abc", Long.class);
        // as in the app, where DefaultErrorAttributes is the first exception resolver
        errorAttributes.resolveException(request, new MockHttpServletResponse(), null, ex);

        ResponseEntity<Object> response =
                handler.handleTypeMismatch(
                        ex,
                        new HttpHeaders(),
                        HttpStatus.BAD_REQUEST,
                        new ServletWebRequest(request));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNull(request.getAttribute(RequestDispatcher.ERROR_MESSAGE));
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals(400, body.get("status"));
        // Spring's message, as there is no parameter name to put in its place
        assertEquals(ex.getMessage(), body.get("message"));
    }

    /**
     * A token that the user info endpoint refuses on create: 401 with {@code WWW-Authenticate} and
     * the OAuth 2.0 error, as for other invalid tokens
     */
    @Test
    public void userInfoRefusedHandler_answersAsForInvalidTokens() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/tasks");
        DefaultErrorAttributes errorAttributes = new DefaultErrorAttributes();
        CustomErrorHandler handler = new CustomErrorHandler(errorAttributes);
        UserInfoRefusedException ex = new UserInfoRefusedException();
        // as in the app, where DefaultErrorAttributes is the first exception resolver
        errorAttributes.resolveException(request, new MockHttpServletResponse(), null, ex);

        ResponseEntity<?> response = handler.userInfoRefusedHandler(request, ex);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals(
                "Bearer realm=\"oauth\", error=\"invalid_token\","
                        + " error_description=\"Invalid access token\"",
                response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE));
        assertEquals("no-store", response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals(401, body.get("status"));
        assertEquals("invalid_token", body.get("error"));
        assertEquals("Invalid access token", body.get("error_description"));
        assertEquals("Invalid access token", body.get("message"));
    }

    /** Exceptions get the status of their {@code @ResponseStatus}, the others 500 */
    @Test
    public void commonErrorHandler_statusOfTheException() {
        CustomErrorHandler handler = new CustomErrorHandler(new DefaultErrorAttributes());

        ResponseEntity<?> notFound =
                handler.commonErrorHandler(
                        new MockHttpServletRequest("GET", "/tasks/task-1"),
                        new TaskNotFoundException("task-1"));
        ResponseEntity<?> other =
                handler.commonErrorHandler(
                        new MockHttpServletRequest("GET", "/tasks"),
                        new IllegalStateException("unexpected"));

        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());
        assertEquals(404, ((Map<?, ?>) notFound.getBody()).get("status"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, other.getStatusCode());
        assertEquals("/tasks", ((Map<?, ?>) other.getBody()).get("path"));
    }

    /** Invalid requests list the errors of the fields and of the object as a whole */
    @Test
    public void handleMethodArgumentNotValid_listsFieldAndObjectErrors() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/tasks");
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "tesTask");
        result.addError(new FieldError("tesTask", "executors", "must not be null"));
        result.addError(new ObjectError("tesTask", "needs an executor"));
        MethodArgumentNotValidException ex =
                new MethodArgumentNotValidException(
                        new MethodParameter(
                                CustomErrorHandlerTest.class.getDeclaredMethod(
                                        "createTask", Object.class),
                                0),
                        result);

        ResponseEntity<Object> response =
                new CustomErrorHandler(new DefaultErrorAttributes())
                        .handleMethodArgumentNotValid(
                                ex,
                                new HttpHeaders(),
                                HttpStatus.BAD_REQUEST,
                                new ServletWebRequest(request));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals(
                Arrays.asList("Field 'executors' must not be null", "tesTask needs an executor"),
                body.get("messages"));
        assertNull(body.get("message"));
        assertNull(body.get("errors"));
    }

    /** The parameter of the invalid request in the test above */
    @SuppressWarnings("unused")
    private static void createTask(Object task) {}
}
