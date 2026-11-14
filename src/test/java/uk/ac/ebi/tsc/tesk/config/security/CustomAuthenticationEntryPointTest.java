package uk.ac.ebi.tsc.tesk.config.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import org.junit.Test;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
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

    private static Map<String, Object> body(MockHttpServletResponse response) throws Exception {
        return new ObjectMapper()
                .readValue(
                        response.getContentAsString(), new TypeReference<Map<String, Object>>() {});
    }

    /** Without a message (null or blank), the description is the reason phrase of 401 */
    @Test
    public void blankMessage_isDescribedAsUnauthorized() throws Exception {
        for (String message : new String[] {null, "", " "}) {
            MockHttpServletResponse response =
                    commence(null, new InsufficientAuthenticationException(message));
            assertEquals(401, response.getStatus());
            assertEquals(
                    "Bearer realm=\"oauth\", error=\"unauthorized\","
                            + " error_description=\"Unauthorized\"",
                    response.getHeader("WWW-Authenticate"));
            assertEquals("unauthorized", body(response).get("error"));
            assertEquals("Unauthorized", body(response).get("error_description"));
        }
    }

    /** An OAuth 2.0 error without a description is described by the exception's message */
    @Test
    public void oauth2ErrorWithoutDescription_hasTheMessage() throws Exception {
        MockHttpServletResponse response =
                commence(
                        null,
                        new OAuth2AuthenticationException(
                                new OAuth2Error("invalid_token"), "Token refused"));
        assertEquals(
                "Bearer realm=\"oauth\", error=\"invalid_token\", error_description=\"Token refused\"",
                response.getHeader("WWW-Authenticate"));
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
        Map<String, Object> body = body(response);
        assertEquals(
                new HashSet<>(
                        Arrays.asList(
                                "timestamp",
                                "status",
                                "error",
                                "message",
                                "path",
                                "error_description")),
                body.keySet());
        assertEquals(401, body.get("status"));
        assertEquals("invalid_token", body.get("error"));
        assertEquals("Invalid access token", body.get("message"));
        assertEquals("Invalid access token", body.get("error_description"));
        assertEquals("/tasks", body.get("path"));
    }

    @Test
    public void jsonAccepted_isWritten() throws Exception {
        MockHttpServletResponse response =
                commence(
                        "text/html;q=0.5, application/json",
                        new InsufficientAuthenticationException("no token"));
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        assertEquals("unauthorized", body(response).get("error"));
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
        assertEquals("unauthorized", body(response).get("error"));
    }
}
