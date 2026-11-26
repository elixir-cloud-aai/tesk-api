package uk.ac.ebi.tsc.tesk.accessmatrix;

import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import uk.ac.ebi.tsc.tesk.accessmatrix.Callers.Caller;
import uk.ac.ebi.tsc.tesk.accessmatrix.Matrix.Operation;

/**
 * Matrix variant: service info with {@code serviceInfoPublic: false} needs a valid token, so rows
 * 1, 10-13 and 17 get 401 and row 16 (untrusted actor) 403, as on every other operation; the others
 * 200 (design section 2, variants). Pending cells: configuration {@code service-info-private} in
 * {@code access-matrix/pending.csv}.
 */
@TestPropertySource(properties = "tesk.api.service-info.public=false")
class AccessMatrixServiceInfoPrivateIT extends Harness {

    private static final Set<Integer> UNAUTHENTICATED = Set.of(1, 10, 11, 12, 13, 17);

    private static final Set<Integer> FORBIDDEN = Set.of(16);

    /** The mock Kubernetes API (nested, so that it replaces the application's client) */
    @TestConfiguration
    static class Kubernetes extends KubernetesClientMock {}

    @Override
    String config() {
        return "service-info-private";
    }

    static Stream<Caller> callers() {
        return Callers.all().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("callers")
    void serviceInfo(Caller caller) throws Exception {
        String token = prepare(caller);
        MvcResult result = perform(Operation.SI, token, null);
        String expected;
        if (UNAUTHENTICATED.contains(caller.row())) {
            expected = "401";
        } else if (FORBIDDEN.contains(caller.row())) {
            expected = "403";
        } else {
            expected = "200";
        }
        check(caller.id(), "SI", result, expected, cell(expected, Operation.SI, null));
    }
}
