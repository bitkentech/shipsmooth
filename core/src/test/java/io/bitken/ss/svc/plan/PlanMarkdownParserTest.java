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
    @Test
    public void historicalPlansWithParsedTasksProduceNoDiagnostics() throws Exception {
        Path plansDir = Paths.get("../.shipsmooth/plans");
        try (var files = Files.list(plansDir)) {
            for (Path p : files
                    .filter(f -> f.getFileName().toString().matches("plan-\\d+\\.md"))
                    .filter(f -> Files.exists(siblingTasksXml(f)))
                    .sorted().toList()) {
                var result = parser.parseWithDiagnostics(Files.readString(p));
                assertFalse(result.tasks().isEmpty(), p + " should parse to tasks");
                assertTrue(result.diagnostics().isEmpty(),
                        p + " must produce no diagnostics, got: " + result.diagnostics());
            }
        }
    }

    private Path siblingTasksXml(Path planMd) {
        return planMd.resolveSibling(
                planMd.getFileName().toString().replace(".md", "-tasks.xml"));
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
