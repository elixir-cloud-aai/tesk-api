package uk.ac.ebi.tsc.tesk.accessmatrix;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.MvcResult;
import uk.ac.ebi.tsc.tesk.accessmatrix.Callers.Caller;
import uk.ac.ebi.tsc.tesk.accessmatrix.Matrix.Operation;
import uk.ac.ebi.tsc.tesk.accessmatrix.Matrix.Row;

/**
 * The matrix as a parametrised test: one test per row, caller variant, task variant and operation
 * of {@code access-matrix/matrix.txt} (cells {@code -} are no tests). For a row whose task is
 * {@code none/foreign}, both are tested ({@code none}: get and cancel of a missing ID). The mock
 * cluster always holds a task of someone else, and a task of the caller where the caller owns one
 * ({@code own}, or where its list cell is {@code 200o}).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class MatrixHarness extends Harness {

    /** A test case: a cell of the matrix for one caller and task variant */
    record Case(Caller caller, String task, Operation operation, String expected, String id) {
        @Override
        public String toString() {
            return "row "
                    + id
                    + " "
                    + operation.column
                    + ": "
                    + expected
                    + " ["
                    + caller.what()
                    + "]";
        }
    }

    /** Whether the configuration tests this caller (e.g. not those of a decision service down) */
    boolean includes(Caller caller) {
        return true;
    }

    Stream<Arguments> cases() {
        List<Arguments> cases = new ArrayList<>();
        for (Row row : Matrix.rows()) {
            List<String> tasks = tasks(row.task());
            for (Caller caller : Callers.ofRow(row.number())) {
                if (!includes(caller)) {
                    continue;
                }
                for (String task : tasks) {
                    String id = caller.id() + (tasks.size() > 1 ? "/" + task : "");
                    row.cells()
                            .forEach((operation, expected) -> {
                                if (!expected.equals("-")) {
                                    cases.add(
                                            Arguments.of(
                                                    new Case(
                                                            caller, task, operation, expected,
                                                            id)));
                                }
                            });
                }
            }
        }
        if (cases.isEmpty()) {
            throw new IllegalStateException("No cases in " + Matrix.RESOURCE);
        }
        checkCallers();
        return cases.stream();
    }

    /**
     * Checks that the rows are numbered 1 to n, that every row has callers and every caller a row,
     * so that a row added to the table can't go untested
     */
    static void checkCallers() {
        List<Row> rows = Matrix.rows();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).number() != i + 1) {
                throw new IllegalStateException("Rows of " + Matrix.RESOURCE + " not 1 to n");
            }
            if (Callers.ofRow(i + 1).isEmpty()) {
                throw new IllegalStateException("No callers (Callers) for row " + (i + 1));
            }
        }
        for (Caller caller : Callers.all()) {
            if (caller.row() < 1 || caller.row() > rows.size()) {
                throw new IllegalStateException("Caller " + caller + " of no row");
            }
        }
    }

    /** The task variants of the matrix's task column */
    static List<String> tasks(String column) {
        return switch (column) {
            case "own", "any", "subj's" -> List.of("own");
            case "foreign" -> List.of("foreign");
            case "none" -> List.of("none");
            case "none/foreign" -> List.of("none", "foreign");
            default -> throw new IllegalArgumentException("Unknown task column " + column);
        };
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void matrix(Case c) throws Exception {
        String token = prepare(c.caller());
        MockCluster cluster =
                new MockCluster(kubernetes)
                        .task(
                                FOREIGN,
                                Owners.of(MockIdp.ISSUER, Callers.SOMEONE_ELSE),
                                Callers.TEAM);
        boolean ownsTask =
                c.task().equals("own")
                        || "200o".equals(Matrix.row(c.caller().row()).cells().get(Operation.LIST));
        if (ownsTask && c.caller().owner() != null) {
            cluster.task(OWN, c.caller().owner(), c.caller().team());
        }
        cluster.stub();
        String taskId =
                switch (c.task()) {
                    case "own" -> OWN;
                    case "foreign" -> FOREIGN;
                    default -> MISSING;
                };
        MvcResult result = perform(c.operation(), token, taskId);
        check(
                c.id(),
                c.operation().column,
                result,
                c.expected(),
                cell(c.expected(), c.operation(), taskId));
    }
}
