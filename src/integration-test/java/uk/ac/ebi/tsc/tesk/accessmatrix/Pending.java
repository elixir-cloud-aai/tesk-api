package uk.ac.ebi.tsc.tesk.accessmatrix;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The cells that the current code doesn't meet yet, read from {@code access-matrix/pending.csv}:
 * per configuration, case and operation, the slice of the redesign that makes it pass, and the
 * status that the current code answers. The tests of these cells check that the current code still
 * answers that status and does not meet the target yet (a strict expected failure); they then end
 * as aborted (skipped), naming the slice. A cell that meets its target fails its test until its
 * line is removed. The slice {@code decide} marks cells whose target needs a decision first: they
 * are skipped without checks.
 */
final class Pending {

    static final String RESOURCE = "/access-matrix/pending.csv";

    /** The slice of cells whose target isn't decided yet */
    static final String DECIDE = "decide";

    private static final Pattern SLICE = Pattern.compile("T[0-9][a-z]?|decide");

    /** A pending cell: the slice that makes it pass, and today's status (0 for {@code decide}) */
    record Entry(String slice, int today) {}

    private static Map<String, Entry> entries;

    private Pending() {}

    /** The entry of the cell, if it is pending */
    static synchronized Optional<Entry> find(String config, String caseId, String operation) {
        if (entries == null) {
            entries = read();
        }
        return Optional.ofNullable(entries.get(key(config, caseId, operation)));
    }

    private static String key(String config, String caseId, String operation) {
        return config + "," + caseId + "," + operation;
    }

    private static Map<String, Entry> read() {
        String text;
        try (InputStream in = Pending.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + RESOURCE);
            }
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + RESOURCE, e);
        }
        Map<String, Entry> result = new LinkedHashMap<>();
        boolean header = true;
        for (String line : text.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (header) {
                if (!trimmed.equals("config,case,operation,slice,today")) {
                    throw new IllegalStateException(
                            "Unexpected header of " + RESOURCE + ": " + line);
                }
                header = false;
                continue;
            }
            String[] fields = trimmed.split(",", -1);
            if (fields.length != 5 || !SLICE.matcher(fields[3]).matches()) {
                throw new IllegalStateException("Invalid line of " + RESOURCE + ": " + line);
            }
            int today = DECIDE.equals(fields[3]) ? 0 : Integer.parseInt(fields[4]);
            Entry previous =
                    result.put(key(fields[0], fields[1], fields[2]), new Entry(fields[3], today));
            if (previous != null) {
                throw new IllegalStateException("Duplicate line of " + RESOURCE + ": " + line);
            }
        }
        return result;
    }
}
