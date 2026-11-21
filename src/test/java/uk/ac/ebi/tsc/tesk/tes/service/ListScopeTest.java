package uk.ac.ebi.tsc.tesk.tes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import uk.ac.ebi.tsc.tesk.tes.exception.InvalidPageTokenException;

/** Page tokens bound to the list scope of the decision service */
public class ListScopeTest {

    @Test
    public void of() {
        assertThat(ListScope.of(true)).isEqualTo(ListScope.ALL);
        assertThat(ListScope.of(false)).isEqualTo(ListScope.OWN);
    }

    @Test
    public void pageToken_roundTrip() {
        assertThat(ListScope.OWN.pageToken("abc")).isEqualTo("own:abc");
        assertThat(ListScope.OWN.continueToken("own:abc")).isEqualTo("abc");
        assertThat(ListScope.ALL.pageToken("abc")).isEqualTo("all:abc");
        assertThat(ListScope.ALL.continueToken("all:abc")).isEqualTo("abc");
    }

    @Test
    public void pageToken_noNextPage() {
        assertThat(ListScope.ALL.pageToken(null)).isNull();
        assertThat(ListScope.ALL.pageToken("")).isNull();
    }

    @Test
    public void continueToken_firstPage() {
        assertThat(ListScope.ALL.continueToken(null)).isNull();
        assertThat(ListScope.ALL.continueToken("")).isNull();
    }

    /** Case 13b: a token of the other scope, without a scope, or empty after it gives 400 */
    @Test
    public void continueToken_otherScope() {
        assertThatThrownBy(() -> ListScope.OWN.continueToken("all:abc"))
                .isInstanceOf(InvalidPageTokenException.class);
        assertThatThrownBy(() -> ListScope.ALL.continueToken("abc"))
                .isInstanceOf(InvalidPageTokenException.class);
        assertThatThrownBy(() -> ListScope.ALL.continueToken("all:"))
                .isInstanceOf(InvalidPageTokenException.class);
    }
}
