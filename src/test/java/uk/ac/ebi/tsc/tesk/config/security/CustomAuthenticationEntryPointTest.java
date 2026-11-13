package uk.ac.ebi.tsc.tesk.config.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import org.junit.Test;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/** The answers to requests without a valid token */
public class CustomAuthenticationEntryPointTest {

    private final CustomAuthenticationEntryPoint entryPoint =
            new CustomAuthenticationEntryPoint(
                    new DefaultErrorAttributes(),
                    new HttpMessageConverters(
                            false,
                            Collections.singletonList(new MappingJackson2HttpMessageConverter())));

    private MockHttpServletResponse commence(String accept, AuthenticationException e)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/tasks");
        if (accept != null) {
            request.addHeader("Accept", accept);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        entryPoint.commence(request, response, e);
        return response;
    }

    @Test
    public void wwwAuthenticate_escapesQuotes() {
        assertEquals(
                "Bearer realm=\"oauth\", error=\"invalid_token\","
                        + " error_description=\"a \\\"b\\\" \\\\c\"",
                CustomAuthenticationEntryPoint.wwwAuthenticate("invalid_token", "a \"b\" \\c"));
    }

    @Test
    public void invalidToken_isDescribed() throws Exception {
        MockHttpServletResponse response =
                commence(null, new InvalidBearerTokenException("Invalid access token"));
        assertEquals(401, response.getStatus());
        assertEquals(
                "Bearer realm=\"oauth\", error=\"invalid_token\","
                        + " error_description=\"Invalid access token\"",
                response.getHeader("WWW-Authenticate"));
        assertTrue(response.getContentAsString().contains("\"error\":\"invalid_token\""));
        assertTrue(
                response.getContentAsString()
                        .contains("\"error_description\":\"Invalid access token\""));
    }

    @Test
    public void jsonAccepted_isWritten() throws Exception {
        MockHttpServletResponse response =
                commence(
                        "text/html;q=0.5, application/json",
                        new InsufficientAuthenticationException("no token"));
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        assertTrue(response.getContentAsString().contains("\"error\":\"unauthorized\""));
    }

    /** Without a converter for an accepted type, the answer has the status and headers only */
    @Test
    public void nothingAccepted_hasNoBody() throws Exception {
        MockHttpServletResponse response =
                commence("text/html", new InsufficientAuthenticationException("no token"));
        assertEquals(401, response.getStatus());
        assertEquals("", response.getContentAsString());
        assertEquals(
                "Bearer realm=\"oauth\", error=\"unauthorized\", error_description=\"no token\"",
                response.getHeader("WWW-Authenticate"));
    }

    @Test
    public void invalidAccept_isTakenAsAll() throws Exception {
        MockHttpServletResponse response =
                commence("not a media type", new InsufficientAuthenticationException("no token"));
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"error\":\"unauthorized\""));
    }
}
