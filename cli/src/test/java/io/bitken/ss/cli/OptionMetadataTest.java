package io.bitken.ss.cli;

import io.bitken.ss.conf.AppComponents;
import io.bitken.ss.conf.DaggerAppComponents;
import io.bitken.ss.conf.ExperimentalMode;
import io.bitken.ss.conf.ServicesModule;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Model.OptionSpec;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 2 (plan-83): every documented option must carry a non-empty description
 * and a meaningful param label (not the picocli default {@code PARAM}). Pins the
 * previously under-documented options across the {@code plan} and {@code task}
 * leaves so their {@code --help} output is informative and consistent.
 */
public class OptionMetadataTest {

    private final AppComponents app = DaggerAppComponents.builder()
            .servicesModule(new ServicesModule(Paths.get("."), new ExperimentalMode(true)))
            .build();
    private final CommandLine root = new CommandTree(app).commandLine();

    private OptionSpec option(String group, String leaf, String name) {
        OptionSpec opt = root.getSubcommands().get(group)
                .getSubcommands().get(leaf)
                .getCommandSpec().findOption(name);
        assertNotNull(opt, "`" + group + " " + leaf + " " + name + "` should exist");
        return opt;
    }

    private void assertDocumented(String group, String leaf, String name, String expectedLabel) {
        OptionSpec opt = option(group, leaf, name);
        String desc = String.join(" ", opt.description()).trim();
        assertFalse(desc.isEmpty(),
                "`" + group + " " + leaf + " " + name + "` should have a description");
        assertEquals(expectedLabel, opt.paramLabel(),
                "`" + group + " " + leaf + " " + name + "` should use a meaningful param label");
    }

    @Test
    void planShowOptionsDocumented() {
        assertDocumented("plan", "show", "--plan", "PLAN_NUMBER");
    }

    @Test
    void planUpdateOptionsDocumented() {
        assertDocumented("plan", "update", "--plan", "PLAN_NUMBER");
        assertDocumented("plan", "update", "--status", "STATUS");
        assertDocumented("plan", "update", "--message", "TEXT");
        // --blocked is a flag; it only needs a description, no param label.
        String blocked = String.join(" ", option("plan", "update", "--blocked").description()).trim();
        assertFalse(blocked.isEmpty(), "`plan update --blocked` should have a description");
    }

    @Test
    void planQuickOptionsDocumented() {
        assertDocumented("plan", "quick", "--desc", "TEXT");
    }

    @Test
    void taskStatusOptionsDocumented() {
        assertDocumented("task", "status", "--plan", "PLAN_NUMBER");
        assertDocumented("task", "status", "--task", "TASK_ID");
        assertDocumented("task", "status", "--status", "STATUS");
    }

    private void assertDescriptionContains(String group, String leaf, String name, String... needles) {
        String desc = String.join(" ", option(group, leaf, name).description());
        for (String needle : needles) {
            assertTrue(desc.contains(needle),
                    "`" + group + " " + leaf + " " + name + "` description should list \""
                            + needle + "\"; was: " + desc);
        }
    }

    @Test
    void planTagKindEnumeratesValues() {
        assertDescriptionContains("plan", "tag", "--kind", "version", "complete", "abandoned");
    }

    @Test
    void planUpdateStatusEnumeratesValues() {
        assertDescriptionContains("plan", "update", "--status",
                "active", "complete", "abandoned", "in-review");
    }

    @Test
    void taskStatusEnumeratesValues() {
        assertDescriptionContains("task", "status", "--status",
                "pending", "in-progress", "de-risked", "agent-coded", "closed",
                "needs-triage", "abandoned");
    }

    @Test
    void taskDeviationTypeEnumeratesValues() {
        assertDescriptionContains("task", "deviation", "--type", "minor", "major");
    }

    @Test
    void taskSetCommitOptionsDocumented() {
        assertDocumented("task", "set-commit", "--plan", "PLAN_NUMBER");
        assertDocumented("task", "set-commit", "--task", "TASK_ID");
        assertDocumented("task", "set-commit", "--commit", "HASH");
        assertDocumented("task", "set-commit", "--branch", "BRANCH");
    }

    @Test
    void planPreflightOptionsDocumented() {
        assertDocumented("plan", "preflight", "--plan", "PLAN_NUMBER");
    }

    @Test
    void planBranchOptionsDocumented() {
        assertDocumented("plan", "branch", "--issue", "ISSUE_ID");
        assertDocumented("plan", "branch", "--plan", "PLAN_NUMBER");
        assertDocumented("plan", "branch", "--desc", "TEXT");
    }

    @Test
    void planResumeOptionsDocumented() {
        assertDocumented("plan", "resume", "--plan", "PLAN_NUMBER");
    }
}
