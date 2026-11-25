package uk.ac.ebi.tsc.tesk.accessmatrix;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import uk.ac.ebi.tsc.tesk.accessmatrix.Callers.Caller;
import uk.ac.ebi.tsc.tesk.accessmatrix.Matrix.Operation;

/**
 * Matrix variant of D-407: all four operations switched on, get, list and cancel failing open, and
 * the decision service down. Failing open falls back to TESK's owner-only decision, never to
 * allow-all, and create can't fail open (design section 4, as proposed in Q7, which is open: board
 * d67); so own tasks stay reachable, foreign ones don't, and create fails closed. Pending cells:
 * configuration {@code fail-open}.
 */
@TestPropertySource(
        properties = {
            "tesk.api.authz.operations=create,get,list,cancel",
            "tesk.api.authz.failure.get=open",
            "tesk.api.authz.failure.list=open",
            "tesk.api.authz.failure.cancel=open"
        })
class AccessMatrixFailOpenIT extends Harness {

    /** The mock Kubernetes API (nested, so that it replaces the application's client) */
    @TestConfiguration
    static class Kubernetes extends KubernetesClientMock {}

    @Override
    String config() {
        return "fail-open";
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("owner", "3", Operation.CREATE, OWN, "503"),
                Arguments.of("owner", "3", Operation.GET_MINIMAL, OWN, "200"),
                Arguments.of("owner", "3", Operation.GET_BASIC, OWN, "200"),
                Arguments.of("owner", "3", Operation.GET_FULL, OWN, "200"),
                Arguments.of("owner", "3", Operation.LIST, OWN, "200o"),
                Arguments.of("owner", "3", Operation.CANCEL, OWN, "200"),
                Arguments.of("foreign", "5", Operation.GET_MINIMAL, FOREIGN, "404"),
                Arguments.of("foreign", "5", Operation.CANCEL, FOREIGN, "404"));
    }

    /**
     * The owner gets, lists and cancels own tasks, create fails closed, and foreign tasks stay out
     * of reach
     */
    @ParameterizedTest(name = "{0} (row {1}) {2}: {4}")
    @MethodSource("cases")
    void decisionServiceDown(
            String caseId, String callerId, Operation operation, String taskId, String expected)
            throws Exception {
        Caller caller = Callers.byId(callerId);
        String token = prepare(caller);
        PDP.mode(MockPdp.Mode.DOWN);
        new MockCluster(kubernetes)
                .task(OWN, caller.owner(), caller.team())
                .task(FOREIGN, Owners.of(MockIdp.ISSUER, Callers.SOMEONE_ELSE), Callers.TEAM)
                .stub();
        MvcResult result = perform(operation, token, taskId);
        check(caseId, operation.column, result, expected, cell(expected, operation, taskId));
    }
}
