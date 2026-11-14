package uk.ac.ebi.tsc.tesk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

/** The helpers of the tests */
public class TestUtilsTest {

    /**
     * Empty arrays and objects go from the executors' jobs, also objects that become empty, but not
     * elements of arrays; the rest of the input stays as it is
     */
    @Test
    public void withoutEmptyInExecutors() {
        assertThat(
                        TestUtils.withoutEmptyInExecutors(
                                "{\"outputs\":[],\"volumes\":{},\"executors\":[{\"a\":[],"
                                        + "\"b\":{\"c\":{},\"d\":[]},\"e\":[{\"f\":[]}],"
                                        + "\"g\":0,\"h\":\"\"}]}"))
                .isEqualTo(
                        "{\"outputs\":[],\"volumes\":{},\"executors\":[{\"e\":[{}],"
                                + "\"g\":0,\"h\":\"\"}]}");
    }
}
