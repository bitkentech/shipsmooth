package io.bitken.ss.svc.plan;

import io.bitken.ss.gw.TaskStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses plan-{N}.md narrative markdown into {@link TaskStore.Task} records.
 *
 * <p>Split out from {@code TaskStore} because Markdown parsing has nothing to do
 * with XML marshalling — and the regex patterns are stable while the JAXB schema
 * evolves on a different cadence.
 */
public class PlanMarkdownParser {

    private static final Pattern HEADING = Pattern.compile(
            "^###\\s+Task\\s+(\\d+):\\s+(.+?)(?:\\s+\\[(High|Medium|Low)\\])?\\s*$",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    // Matches an optional "*Depends-on: 1,2,3*" line anywhere after the heading
    private static final Pattern DEPENDS_ON = Pattern.compile(
            "^\\*Depends-on:\\s*([\\d,\\s]+)\\*\\s*$",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    private static final String GRAMMAR = "### Task N: Name [High|Medium|Low]";

    // A line that structurally attempts a task heading but misses the grammar.
    private static final Pattern ANY_LEVEL_TASK_HEADING = Pattern.compile(
            "^(#{1,6})\\s+Task\\s+(.*)$", Pattern.CASE_INSENSITIVE);
    // Requires ':' or '-' after the number: bold prose mentioning a task
    // ("**Task 1 de-risk** built …") is not an attempted heading.
    private static final Pattern BOLD_TASK_HEADING = Pattern.compile(
            "^\\*\\*\\s*Task\\s+\\d+\\s*[:\\-].*$", Pattern.CASE_INSENSITIVE);
    // A valid heading whose name swallowed a trailing [tag] because the tag is
    // not a recognised risk level.
    private static final Pattern TRAILING_TAG = Pattern.compile(".*\\[([A-Za-z]+)\\]$");

    /** One skipped or suspicious line: where it is, what it says, why it was flagged. */
    public record Diagnostic(int line, String text, String reason) {}

    /** Parsed tasks plus the near-miss lines the strict grammar skipped. */
    public record ParseResult(List<TaskStore.Task> tasks, List<Diagnostic> diagnostics) {}

    public ParseResult parseWithDiagnostics(String markdown) {
        List<TaskStore.Task> tasks = parse(markdown);
        List<Diagnostic> diagnostics = new ArrayList<>();
        String[] lines = markdown.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].stripTrailing();
            int lineNo = i + 1;
            Matcher valid = HEADING.matcher(line);
            if (valid.matches()) {
                riskTagDiagnostic(lineNo, line, valid).ifPresent(diagnostics::add);
                continue;
            }
            nearMissDiagnostic(lineNo, line).ifPresent(diagnostics::add);
        }
        return new ParseResult(tasks, diagnostics);
    }

    private Optional<Diagnostic> riskTagDiagnostic(int lineNo, String line, Matcher valid) {
        if (valid.group(3) != null) {
            return Optional.empty();
        }
        Matcher tag = TRAILING_TAG.matcher(valid.group(2).trim());
        if (tag.matches()) {
            return Optional.of(new Diagnostic(lineNo, line,
                    "unrecognized risk tag [" + tag.group(1) + "] — expected " + GRAMMAR));
        }
        return Optional.empty();
    }

    private Optional<Diagnostic> nearMissDiagnostic(int lineNo, String line) {
        Matcher heading = ANY_LEVEL_TASK_HEADING.matcher(line);
        if (heading.matches()) {
            String afterTask = heading.group(2);
            // Attempted task heading only on strong evidence: a ':' or hyphen
            // right after the id, a word id with colon, or a trailing risk-style
            // tag. Plain prose headings ("## Task ordering note") and em-dash
            // notes headings ("### Task 7 — follow-up research …") stay
            // unflagged — em-dash is deliberate prose punctuation, hyphen is the
            // classic wrong-guess heading separator.
            boolean idWithSeparator = afterTask.matches("\\d+\\s*[:\\-].*");
            boolean wordIdWithColon = afterTask.matches("[A-Za-z]+\\s*:.*");
            boolean idWithRiskTag = afterTask.matches("\\d.*")
                    && TRAILING_TAG.matcher(afterTask).matches();
            if (!idWithSeparator && !wordIdWithColon && !idWithRiskTag) {
                return Optional.empty();
            }
            if (!"###".equals(heading.group(1))) {
                return Optional.of(new Diagnostic(lineNo, line,
                        "task heading must be an h3: " + GRAMMAR));
            }
            if (wordIdWithColon && !afterTask.matches("\\d.*")) {
                return Optional.of(new Diagnostic(lineNo, line,
                        "task id must be a number: " + GRAMMAR));
            }
            return Optional.of(new Diagnostic(lineNo, line,
                    "expected ':' after the task number: " + GRAMMAR));
        }
        if (BOLD_TASK_HEADING.matcher(line).matches()) {
            return Optional.of(new Diagnostic(lineNo, line,
                    "task heading must be an h3 (### …), not bold text: " + GRAMMAR));
        }
        return Optional.empty();
    }

    public List<TaskStore.Task> parse(String markdown) {
        List<TaskStore.Task> tasks = new ArrayList<>();
        Matcher matcher = HEADING.matcher(markdown);
        while (matcher.find()) {
            int id = Integer.parseInt(matcher.group(1));
            String name = matcher.group(2).trim();
            String risk = matcher.group(3) != null ? matcher.group(3).toLowerCase() : "";
            int searchEnd = Math.min(matcher.end() + 500, markdown.length());
            Matcher nextHeading = HEADING.matcher(markdown.substring(matcher.end(), searchEnd));
            int regionEnd = nextHeading.find() ? matcher.end() + nextHeading.start() : searchEnd;
            String region = markdown.substring(matcher.end(), regionEnd);
            Matcher depMatcher = DEPENDS_ON.matcher(region);
            String dependsOn = depMatcher.find() ? depMatcher.group(1).replaceAll("\\s", "") : "";
            tasks.add(new TaskStore.Task(id, name, risk, dependsOn));
        }
        return tasks;
    }
}
