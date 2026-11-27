package uk.ac.ebi.tsc.tesk.config.security.token;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Which URLs TESK API calls for decisions about callers: https, or http to the local host */
public class SecureUrlsTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://pdp.example.org/access/v1/evaluation",
                "http://localhost:8181/access/v1/evaluation",
                "http://127.0.0.1/",
                "http://[::1]:8181/"
            })
    public void secure(String url) {
        assertTrue(SecureUrls.isSecure(url));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://pdp.example.org/",
                "https:///path",
                "ftp://localhost/",
                "localhost:8181",
                "https://pdp example/",
                ""
            })
    public void notSecure(String url) {
        assertFalse(SecureUrls.isSecure(url));
    }

    @Test
    public void null_isNotSecure() {
        assertFalse(SecureUrls.isSecure(null));
    }
}
