package uk.ac.ebi.tsc.tesk.accessmatrix;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import uk.ac.ebi.tsc.tesk.accessmatrix.Callers.Caller;
import uk.ac.ebi.tsc.tesk.accessmatrix.Matrix.Operation;

/**
 * Matrix variants of D-407: with all four operations switched on ({@code authz.operations}) and the
 * reference policy, every row gives the results of the default (the reference policy is
 * owner-only), now decided by the decision service, which is asked once per operation. With the
 * decision service down, each operation fails closed (503, the default); a policy that answers the
 * list scope {@code all} or {@code none} gives all tasks or none (TESK filters); a policy that
 * allows get and cancel of a foreign task widens the owner rule. Rows 18a-c (the decision service
 * failing) have their own test here. Pending cells: configuration {@code all-operations}.
 */
@TestPropertySource(properties = "tesk.api.authz.operations=create,get,list,cancel")
class AccessMatrixAllOperationsIT extends MatrixHarness {

    /** The mock Kubernetes API (nested, so that it replaces the application's client) */
    @TestConfiguration
    static class Kubernetes extends KubernetesClientMock {}

    @Override
    String config() {
        return "all-operations";
    }

    @Override
    boolean includes(Caller caller) {
        return caller.row() != 18;
    }

    /** The action that TESK asks for an operation */
    private static String action(Operation operation) {
        return switch (operation) {
            case CREATE -> "CreateTask";
            case LIST -> "ListTasks";
            case CANCEL -> "CancelTask";
            default -> "GetTask";
        };
    }

    /** Each switched-on operation asks the decision service once, for the owner's task */
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Operation.class, names = "SI", mode = EnumSource.Mode.EXCLUDE)
    void everyOperation_asksDecisionService(Operation operation) throws Exception {
        Caller caller = Callers.byId("3");
        String token = prepare(caller);
        new MockCluster(kubernetes).task(OWN, caller.owner(), caller.team()).stub();
        MvcResult result = perform(operation, token, OWN);
        check(
                "asks-pdp",
                operation.column,
                result,
                "one " + action(operation) + " evaluation",
                r -> {
                    assertEquals(200, r.getResponse().getStatus(), "status");
                    assertEquals(1, PDP.requests(action(operation)).size(), action(operation));
                });
    }

    /** The failure kinds of the decision service, by the case name in {@code pending.csv} */
    static Stream<Arguments> failures() {
        return Stream.of(
                        Arguments.of("pdp-down", MockPdp.Mode.DOWN),
                        Arguments.of("pdp-timeout", MockPdp.Mode.TIMEOUT),
                        Arguments.of("pdp-bad-answer", MockPdp.Mode.BAD_ANSWER))
                .flatMap(failure ->
                        Stream.of(Operation.values())
                                .filter(operation -> operation != Operation.SI)
                                .map(operation ->
                                        Arguments.of(
                                                failure.get()[0], failure.get()[1], operation)));
    }

    /**
     * The decision service down, timing out or answering garbage: every switched-on operation fails
     * closed (503)
     */
    @ParameterizedTest(name = "{0} {2}")
    @MethodSource("failures")
    void decisionServiceFailure_failsClosed(String caseId, MockPdp.Mode mode, Operation operation)
            throws Exception {
        Caller caller = Callers.byId("3");
        String token = prepare(caller);
        PDP.mode(mode);
        new MockCluster(kubernetes).task(OWN, caller.owner(), caller.team()).stub();
        MvcResult result = perform(operation, token, OWN);
        check(caseId, operation.column, result, "503", cell("503", operation, OWN));
    }

    /** List scope {@code all} from the policy: TESK lists all tasks (no owner selector) */
    @Test
    void listScopeAll_listsAllTasks() throws Exception {
        Caller caller = Callers.byId("3");
        String token = prepare(caller);
        PDP.mode(MockPdp.Mode.LIST_ALL);
        new MockCluster(kubernetes)
                .task(OWN, caller.owner(), caller.team())
                .task(FOREIGN, Owners.of(MockIdp.ISSUER, Callers.SOMEONE_ELSE), Callers.TEAM)
                .stub();
        MvcResult result = perform(Operation.LIST, token, null);
        check(
                "scope-all",
                "LIST",
                result,
                "200 with all tasks",
                r -> {
                    assertEquals(200, r.getResponse().getStatus(), "status");
                    assertEquals(Set.of(OWN, FOREIGN), Set.copyOf(listed(r)), "tasks");
                });
    }

    /** List scope {@code none}: an empty page, without asking Kubernetes */
    @Test
    void listScopeNone_listsNothing() throws Exception {
        Caller caller = Callers.byId("3");
        String token = prepare(caller);
        PDP.mode(MockPdp.Mode.LIST_NONE);
        new MockCluster(kubernetes).task(OWN, caller.owner(), caller.team()).stub();
        MvcResult result = perform(Operation.LIST, token, null);
        check(
                "scope-none",
                "LIST",
                result,
                "200[] without a Kubernetes request",
                r -> {
                    assertEquals(200, r.getResponse().getStatus(), "status");
                    assertEquals(List.of(), listed(r), "tasks");
                    assertEquals(0, kubernetes.getAllServeEvents().size(), "Kubernetes requests");
                });
    }

    /** A policy that allows get and cancel of foreign tasks widens the owner rule (D-407) */
    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = Operation.class,
            names = {"GET_MINIMAL", "CANCEL"})
    void widenedPolicy_getsAndCancelsForeignTask(Operation operation) throws Exception {
        Caller caller = Callers.byId("5");
        String token = prepare(caller);
        PDP.mode(MockPdp.Mode.WIDEN);
        new MockCluster(kubernetes)
                .task(FOREIGN, Owners.of(MockIdp.ISSUER, Callers.SOMEONE_ELSE), Callers.TEAM)
                .stub();
        MvcResult result = perform(operation, token, FOREIGN);
        check("widen", operation.column, result, "200", cell("200", operation, FOREIGN));
    }
}
