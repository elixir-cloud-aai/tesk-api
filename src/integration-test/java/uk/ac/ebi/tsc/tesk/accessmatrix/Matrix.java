package uk.ac.ebi.tsc.tesk.accessmatrix;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The caller x operation matrix of the access control redesign, read from {@code
 * access-matrix/matrix.txt}: a copy of the table in the design, so that the table and the tests
 * can't drift apart. The columns are found by the dashes under the header; a line whose first
 * column holds a number starts a row, the lines below it continue its caller and task.
 */
final class Matrix {

    /** The columns of the operations, in the order of the table */
    enum Operation {
        SI("SI"),
        CREATE("CREATE"),
        GET_MINIMAL("GET-M"),
        GET_BASIC("GET-B"),
        GET_FULL("GET-F"),
        LIST("LIST"),
        CANCEL("CANCEL");

        final String column;

        Operation(String column) {
            this.column = column;
        }

        static Operation ofColumn(String column) {
            for (Operation operation : values()) {
                if (operation.column.equals(column)) {
                    return operation;
                }
            }
            throw new IllegalArgumentException("Unknown operation column " + column);
        }
    }

    /** A row of the table: its number, caller, task column and the expected cell per operation */
    record Row(int number, String caller, String task, Map<Operation, String> cells) {}

    static final String RESOURCE = "/access-matrix/matrix.txt";

    private static final Pattern ROW_START = Pattern.compile("^(\\d+)\\s");

    private static List<Row> rows;

    private Matrix() {}

    /** The rows of the table, read once */
    static synchronized List<Row> rows() {
        if (rows == null) {
            rows = Collections.unmodifiableList(read());
        }
        return rows;
    }

    static Row row(int number) {
        return rows().stream()
                .filter(row -> row.number() == number)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No matrix row " + number));
    }

    private static List<Row> read() {
        List<String> lines;
        try (InputStream in = Matrix.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + RESOURCE);
            }
            lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + RESOURCE, e);
        }
        int header = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("#   Caller")) {
                header = i;
                break;
            }
        }
        if (header < 0 || header + 1 >= lines.size()) {
            throw new IllegalStateException("No table header in " + RESOURCE);
        }
        List<int[]> spans = spans(lines.get(header + 1));
        List<String> names = new ArrayList<>();
        for (int[] span : spans) {
            names.add(cut(lines.get(header), span));
        }
        if (!names.subList(0, 3).equals(List.of("#", "Caller", "task"))
                || names.size() != 3 + Operation.values().length) {
            throw new IllegalStateException("Unexpected columns " + names + " in " + RESOURCE);
        }
        List<Row> result = new ArrayList<>();
        StringBuilder caller = null;
        StringBuilder task = null;
        int number = 0;
        Map<Operation, String> cells = null;
        for (String line : lines.subList(header + 2, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            Matcher start = ROW_START.matcher(line);
            if (start.find()) {
                if (cells != null) {
                    result.add(new Row(number, caller.toString(), task.toString(), cells));
                }
                number = Integer.parseInt(start.group(1));
                caller = new StringBuilder(cut(line, spans.get(1)));
                task = new StringBuilder(cut(line, spans.get(2)));
                cells = new LinkedHashMap<>();
                for (int i = 3; i < spans.size(); i++) {
                    cells.put(Operation.ofColumn(names.get(i)), cut(line, spans.get(i)));
                }
            } else if (cells != null) {
                // a continuation: the caller and task go on; notes under the cells are comments
                append(caller, cut(line, spans.get(1)));
                append(task, cut(line, spans.get(2)));
            } else {
                throw new IllegalStateException("Continuation before the first row: " + line);
            }
        }
        if (cells != null) {
            result.add(new Row(number, caller.toString(), task.toString(), cells));
        }
        return result;
    }

    /** The column spans of a line of dashes, as start and end (exclusive) */
    private static List<int[]> spans(String dashes) {
        List<int[]> spans = new ArrayList<>();
        Matcher matcher = Pattern.compile("-+").matcher(dashes);
        while (matcher.find()) {
            spans.add(new int[] {matcher.start(), matcher.end()});
        }
        // the last column takes the rest of the line
        spans.get(spans.size() - 1)[1] = Integer.MAX_VALUE;
        return spans;
    }

    /** The text of a column, also of a line that ends within or before it */
    private static String cut(String line, int[] span) {
        if (line.length() <= span[0]) {
            return "";
        }
        // a value may run up to the start of the next column (e.g. "200[]" in a 4-wide one)
        int end = Math.min(line.length(), span[1] == Integer.MAX_VALUE ? line.length() : span[1]);
        int next = end;
        while (next < line.length() && line.charAt(next) != ' ') {
            next++;
        }
        return line.substring(span[0], next).trim();
    }

    private static void append(StringBuilder builder, String text) {
        if (!text.isEmpty()) {
            // "none/" and "foreign" on the next line are "none/foreign"
            boolean space = builder.length() > 0 && builder.charAt(builder.length() - 1) != '/';
            builder.append(space ? " " : "").append(text);
        }
    }
}
