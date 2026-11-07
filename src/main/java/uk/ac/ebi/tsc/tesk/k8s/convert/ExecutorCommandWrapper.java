package uk.ac.ebi.tsc.tesk.k8s.convert;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import uk.ac.ebi.tsc.tesk.tes.model.TesExecutor;

/**
 * Wraps list of executor's command, so that:
 *
 * <ul>
 *   <li>if any of executor's stdin/stdout/stderr params is set, the command runs in shell
 *   <li>Each part of the original command (single command/argument) that contained shell special
 *       chars is surrounded by single quotes, plus single quote inside such string are replaced
 *       with '"'"' sequence
 *   <li>stdin, stdout, stderr streams are redirected to paths according to executors params
 * </ul>
 */
public class ExecutorCommandWrapper {

    private static final Pattern SPECIAL_CHARS =
            Pattern.compile("[ !\"#$&'()*;<>?\\[\\\\`{|~\\t\\n]");
    private final TesExecutor executor;

    public ExecutorCommandWrapper(TesExecutor executor) {
        this.executor = executor;
    }

    public List<String> getCommandsWithStreamRedirects() {
        List<String> result = new ArrayList<>();
        if (StringUtils.isEmpty(executor.getStdin())
                && StringUtils.isEmpty(executor.getStdout())
                && StringUtils.isEmpty(executor.getStderr())) {
            return executor.getCommand();
        }
        result.add("/bin/sh");
        result.add("-c");
        StringJoiner sj = new StringJoiner(" ");
        for (String commandPart : executor.getCommand()) {
            String part = commandPart;
            if (SPECIAL_CHARS.matcher(part).find()) {
                if (part.contains("'")) {
                    part = part.replaceAll("'", "'\"'\"'");
                }
                part = "'" + part + "'";
            }
            sj.add(part);
        }
        if (!StringUtils.isEmpty(executor.getStdin())) {
            sj.add("<");
            sj.add(executor.getStdin());
        }
        if (!StringUtils.isEmpty(executor.getStdout())) {
            sj.add(">");
            sj.add(executor.getStdout());
        }
        if (!StringUtils.isEmpty(executor.getStderr())) {
            sj.add("2>");
            sj.add(executor.getStderr());
        }
        result.add(sj.toString());
        return result;
    }
}
