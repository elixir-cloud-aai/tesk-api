package uk.ac.ebi.tsc.tesk.tes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import uk.ac.ebi.tsc.tesk.tes.exception.InvalidPageTokenException;

/** Page tokens bound to the list scope they were issued under */
public class PageTokensTest {

    @Test
    public void firstPage() {
        assertThat(PageTokens.continueToken(null, false)).isNull();
        assertThat(PageTokens.continueToken(null, true)).isNull();
        assertThat(PageTokens.continueToken("", true)).isNull();
    }

    @Test
    public void own_unchanged() {
        assertThat(PageTokens.continueToken("eyJ2IjoibWV0YS", false)).isEqualTo("eyJ2IjoibWV0YS");
        assertThat(PageTokens.continueToken("", false)).isEmpty();
        assertThat(PageTokens.pageToken("eyJ2IjoibWV0YS", false)).isEqualTo("eyJ2IjoibWV0YS");
        assertThat(PageTokens.pageToken(null, false)).isNull();
        assertThat(PageTokens.pageToken("", false)).isEmpty();
    }

    @Test
    public void all_marked() {
        assertThat(PageTokens.pageToken("eyJ2IjoibWV0YS", true)).isEqualTo("all.eyJ2IjoibWV0YS");
        assertThat(PageTokens.continueToken("all.eyJ2IjoibWV0YS", true))
                .isEqualTo("eyJ2IjoibWV0YS");
        assertThat(PageTokens.pageToken(null, true)).isNull();
        assertThat(PageTokens.pageToken("", true)).isEmpty();
    }

    @Test
    public void otherScope_isRefused() {
        assertThatThrownBy(() -> PageTokens.continueToken("all.eyJ2", false))
                .isInstanceOf(InvalidPageTokenException.class);
        assertThatThrownBy(() -> PageTokens.continueToken("eyJ2", true))
                .isInstanceOf(InvalidPageTokenException.class);
        assertThatThrownBy(() -> PageTokens.continueToken("all.", true))
                .isInstanceOf(InvalidPageTokenException.class);
    }
}
