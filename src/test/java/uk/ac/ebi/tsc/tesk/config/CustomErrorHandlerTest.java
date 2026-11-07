package uk.ac.ebi.tsc.tesk.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Map;
import javax.servlet.RequestDispatcher;
import org.junit.Test;
import org.springframework.beans.TypeMismatchException;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.ServletWebRequest;

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
}
