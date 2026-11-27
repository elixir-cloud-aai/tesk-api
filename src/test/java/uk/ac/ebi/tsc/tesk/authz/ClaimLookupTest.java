package uk.ac.ebi.tsc.tesk.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The lookup order of the tes-authz spec (section 6): exact name in the token, exact name in the
 * user info, path in the token, path in the user info
 */
public class ClaimLookupTest {

    private static final String URI_NAMED = "https://example.org/groups";

    @Test
    public void exactName_tokenBeforeUserInfo() {
        assertThat(ClaimLookup.lookup(Map.of("g", "t"), Map.of("g", "u"), "g")).contains("t");
    }

    @Test
    public void exactName_inUserInfo() {
        assertThat(ClaimLookup.lookup(Map.of(), Map.of("g", List.of("u")), "g"))
                .contains(List.of("u"));
    }

    /** URI-named claims are found by their exact name, not split at the dots */
    @Test
    public void uriNamedClaim() {
        assertThat(ClaimLookup.lookup(Map.of(), Map.of(URI_NAMED, "x"), URI_NAMED)).contains("x");
    }

    /** An exact name in the user info comes before a path in the token */
    @Test
    public void exactNameInUserInfo_beforePathInToken() {
        Map<String, Object> token = Map.of("a", Map.of("b", "path"));
        assertThat(ClaimLookup.lookup(token, Map.of("a.b", "exact"), "a.b")).contains("exact");
    }

    @Test
    public void path_tokenBeforeUserInfo() {
        Map<String, Object> token = Map.of("a", Map.of("b", "t"));
        Map<String, Object> userInfo = Map.of("a", Map.of("b", "u"));
        assertThat(ClaimLookup.lookup(token, userInfo, "a.b")).contains("t");
        assertThat(ClaimLookup.lookup(Map.of(), userInfo, "a.b")).contains("u");
    }

    @Test
    public void missing() {
        Map<String, Object> claims = Map.of("a", Map.of("b", "x"), "s", "string");
        assertThat(ClaimLookup.lookup(claims, Map.of(), "c")).isEmpty();
        assertThat(ClaimLookup.lookup(claims, Map.of(), "a.c")).isEmpty();
        // a path through a value that isn't an object
        assertThat(ClaimLookup.lookup(claims, Map.of(), "s.x")).isEmpty();
    }

    /** A JSON null counts as missing, by name and by path */
    @Test
    public void nullValue_isMissing() {
        Map<String, Object> token = new HashMap<>();
        token.put("n", null);
        Map<String, Object> nested = new HashMap<>();
        nested.put("b", null);
        token.put("a", nested);
        assertThat(ClaimLookup.hasExact(token, "n")).isFalse();
        assertThat(ClaimLookup.lookup(token, Map.of("n", "u"), "n")).contains("u");
        assertThat(ClaimLookup.lookup(token, Map.of(), "a.b")).isEqualTo(Optional.empty());
    }
}
