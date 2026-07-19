package io.bitken.ss.svc.plan;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

public class PlanMarkdownParserTest {

    private final PlanMarkdownParser parser = new PlanMarkdownParser();

    @Test
    public void nearMissHeadingsAreDiagnosedWithLineNumbersAndReasons() {
        String markdown = """
                # Plan — add a done command

                ## Tasks

                ## Task 1: Extend the task model [Low]

                Body text.

                ### Task 2 - Show completion state in view [Low]

                **Task 3: End-to-end test** [Low]
                """;

        PlanMarkdownParser.ParseResult result = parser.parseWithDiagnostics(markdown);

        assertEquals(0, result.tasks().size());
        assertEquals(3, result.diagnostics().size());

        var wrongLevel = result.diagnostics().get(0);
        assertEquals(5, wrongLevel.line());
        assertTrue(wrongLevel.reason().contains("###"),
                "wrong-level reason should name the required h3 form, got: " + wrongLevel.reason());

        var missingColon = result.diagnostics().get(1);
        assertEquals(9, missingColon.line());
        assertTrue(missingColon.reason().contains(":"),
                "dash-for-colon reason should name the missing colon, got: " + missingColon.reason());

        var bold = result.diagnostics().get(2);
        assertEquals(11, bold.line());
        assertTrue(bold.reason().contains("###"),
                "bold-heading reason should steer to h3, got: " + bold.reason());
    }

    @Test
    public void canonicalPlanWithTaskProseProducesNoDiagnostics() {
        String markdown = """
                # Plan 97 — cleanup

                Task ordering is risk-based. This plan touches the task model.

                ## Tasks

                ### Task 1: Fix bug [High]

                *Depends-on: 2*

                Task granularity here follows thin vertical slices.

                ### Task 2: Refactor [Medium]

                ### Task 3: Test
                """;

        PlanMarkdownParser.ParseResult result = parser.parseWithDiagnostics(markdown);

        assertEquals(3, result.tasks().size());
        assertTrue(result.diagnostics().isEmpty(),
                "canonical plan must produce zero diagnostics, got: " + result.diagnostics());
    }

    // Regression sweep: every historical plan file that actually went through
    // `plan init` (has a sibling tasks XML, i.e. its parse was human-reviewed)
    // is known-good input the near-miss heuristic must stay silent on. Plans
    // without a tasks XML predate local tracking and may legitimately trigger
    // diagnostics (e.g. plan-11's dropped "### Task 6 (future): …" heading).
    //
    // Accepted flags (calibration 2026-07-19: dash and em-dash after the task
    // id are separator evidence alike): two notes headings appended to plan
    // bodies during execution, which would legitimately warn at init time.
    private static final java.util.Set<String> ACCEPTED_HISTORICAL_FLAGS = java.util.Set.of(
            "plan-84.md:299",   // "### Task 7 — follow-up research (2026-06-16): …"
            "plan-86.md:244");  // "#### Task 1 — Findings (de-risk run, OpenCode 1.17.9)"

    @Test
    public void historicalPlansWithParsedTasksProduceNoDiagnostics() throws Exception {
        Path plansDir = Paths.get("../.shipsmooth/plans");
        var violations = new java.util.ArrayList<String>();
        try (var files = Files.list(plansDir)) {
            for (Path p : files
                    .filter(f -> f.getFileName().toString().matches("plan-\\d+\\.md"))
                    .filter(f -> Files.exists(siblingTasksXml(f)))
                    .sorted().toList()) {
                var result = parser.parseWithDiagnostics(Files.readString(p));
                assertFalse(result.tasks().isEmpty(), p + " should parse to tasks");
                result.diagnostics().stream()
                        .filter(d -> !ACCEPTED_HISTORICAL_FLAGS.contains(p.getFileName() + ":" + d.line()))
                        .forEach(d -> violations.add(p.getFileName() + ": " + d));
            }
        }
        assertTrue(violations.isEmpty(),
                "reviewed plans must produce no diagnostics, got:\n" + String.join("\n", violations));
    }

    @Test
    public void malformedDependsOnLinesAreDiagnosed() {
        String markdown = """
                ### Task 1: First [Low]

                ### Task 2: Second [Low]

                Depends-on: 1

                ### Task 3: Third [Low]

                *Depends-on: Task 2*
                """;

        PlanMarkdownParser.ParseResult result = parser.parseWithDiagnostics(markdown);

        assertEquals(3, result.tasks().size(), "tasks still parse; only the dependencies are dropped");
        assertEquals(2, result.diagnostics().size());
        assertEquals(5, result.diagnostics().get(0).line());
        assertTrue(result.diagnostics().get(0).reason().contains("*Depends-on: 1,2*"),
                "reason should state the dependency grammar, got: " + result.diagnostics().get(0).reason());
        assertEquals(9, result.diagnostics().get(1).line());
    }

    private Path siblingTasksXml(Path planMd) {
        return planMd.resolveSibling(
                planMd.getFileName().toString().replace(".md", "-tasks.xml"));
    }

    @Test
    public void wordTaskIdIsDiagnosedAsNonNumeric() {
        String markdown = "### Task One: do the thing [Low]\n";

        PlanMarkdownParser.ParseResult result = parser.parseWithDiagnostics(markdown);

        assertEquals(0, result.tasks().size());
        assertEquals(1, result.diagnostics().size());
        assertTrue(result.diagnostics().get(0).reason().contains("number"),
                "word-id reason should require a numeric id, got: " + result.diagnostics().get(0).reason());
    }

    @Test
    public void unrecognizedRiskTagOnParsedTaskIsDiagnosed() {
        String markdown = "### Task 1: Fix the parser [Critical]\n";

        PlanMarkdownParser.ParseResult result = parser.parseWithDiagnostics(markdown);

        assertEquals(1, result.tasks().size(), "task still parses; the bad tag folds into the name");
        assertEquals(1, result.diagnostics().size());
        assertTrue(result.diagnostics().get(0).reason().contains("High|Medium|Low"),
                "reason should name the valid risk tags, got: " + result.diagnostics().get(0).reason());
    }
}
