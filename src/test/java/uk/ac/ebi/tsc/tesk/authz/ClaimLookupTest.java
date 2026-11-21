package uk.ac.ebi.tsc.tesk.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Claim lookup: exact name first, then dotted path */
public class ClaimLookupTest {

    private static Map<String, Object> claims() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("https://e.org/groups", Arrays.asList("a"));
        claims.put("ctx", Collections.singletonMap("groups", "b"));
        claims.put("a.b", "exact");
        claims.put("a", Collections.singletonMap("b", "path"));
        claims.put("empty", null);
        claims.put("scalar", "x");
        return claims;
    }

    @Test
    public void lookup_exactUriName() {
        assertThat(ClaimLookup.lookup(claims(), "https://e.org/groups"))
                .contains(Arrays.asList("a"));
    }

    @Test
    public void lookup_dottedPath() {
        assertThat(ClaimLookup.lookup(claims(), "ctx.groups")).contains("b");
    }

    @Test
    public void lookup_exactBeforePath() {
        assertThat(ClaimLookup.lookup(claims(), "a.b")).contains("exact");
    }

    @Test
    public void lookup_missing() {
        assertThat(ClaimLookup.lookup(claims(), "ctx.other")).isEmpty();
        assertThat(ClaimLookup.lookup(claims(), "scalar.x")).isEmpty();
        assertThat(ClaimLookup.lookup(claims(), "nothing")).isEmpty();
    }

    @Test
    public void lookup_nullValueIsMissing() {
        assertThat(ClaimLookup.lookup(claims(), "empty")).isEmpty();
    }

    @Test
    public void lookup_noClaimsOrName() {
        assertThat(ClaimLookup.lookup(null, "a")).isEmpty();
        assertThat(ClaimLookup.lookup(claims(), null)).isEmpty();
    }
}
