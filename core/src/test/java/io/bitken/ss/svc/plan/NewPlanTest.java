package io.bitken.ss.svc.plan;

import io.bitken.ss.conf.ShipsmoothDataLocator;
import io.bitken.ss.gw.GitState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NewPlanTest {

    @TempDir
    Path repoRoot;

    private final List<String> created = new ArrayList<>();

    @Test
    void scaffoldsBranchAndStubOnFreshRepo() throws Exception {
        ScaffoldResult result = newPlan(notExisting()).scaffold("Desktop UI");

        assertEquals(1, result.planId());
        assertEquals("t/1-desktop-ui", result.branchName());
        assertEquals(List.of("t/1-desktop-ui"), created);
        assertTrue(Files.exists(result.planFile()));
    }

    @Test
    void stubContainsTitleContextAndTasks() throws Exception {
        ScaffoldResult result = newPlan(notExisting()).scaffold("Desktop UI");
        String body = Files.readString(result.planFile());

        assertTrue(body.contains("# plan-1 — Desktop UI"), body);
        assertTrue(body.contains("Stub"), body);
        assertTrue(body.contains("## Context"), body);
        assertTrue(body.contains("Desktop UI"), body);
        assertTrue(body.contains("## Tasks"), body);
    }

    @Test
    void derivesNextPlanNumberFromExistingFiles() throws Exception {
        writePlans("plan-1.md", "plan-4.md");
        ScaffoldResult result = newPlan(notExisting()).scaffold("next one");

        assertEquals(5, result.planId());
        assertEquals("t/5-next-one", result.branchName());
    }

    @Test
    void accentFoldedAndEmptySlugBranchNames() throws Exception {
        assertEquals("t/1-cafe-deja-vu", newPlan(notExisting()).scaffold("Café déjà vu").branchName());
    }

    @Test
    void emptySlugDropsTrailingHyphen() throws Exception {
        assertEquals("t/1", newPlan(notExisting()).scaffold("!!!").branchName());
    }

    @Test
    void collisionThrowsAndWritesNoStub() {
        NewPlan newPlan = newPlan(alwaysExisting());

        ScaffoldException ex = assertThrows(ScaffoldException.class, () -> newPlan.scaffold("Desktop UI"));
        assertTrue(ex.getMessage().contains("already exists"), ex.getMessage());
        assertFalse(Files.exists(repoRoot.resolve(".shipsmooth/plans/plan-1.md")));
        assertTrue(created.isEmpty());
    }

    @Test
    void gitRefusalThrowsAndWritesNoStub() {
        NewPlan newPlan = newPlan(refusingToCreate());

        assertThrows(ScaffoldException.class, () -> newPlan.scaffold("Desktop UI"));
        assertFalse(Files.exists(repoRoot.resolve(".shipsmooth/plans/plan-1.md")));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private NewPlan newPlan(GitState gitState) {
        ShipsmoothDataLocator locator = new ShipsmoothDataLocator(repoRoot);
        return new NewPlan(new PlanNumbers(locator), gitState, locator);
    }

    private void writePlans(String... names) throws IOException {
        Path plansDir = repoRoot.resolve(".shipsmooth/plans");
        Files.createDirectories(plansDir);
        for (String name : names) {
            Files.writeString(plansDir.resolve(name), "x");
        }
    }

    private GitState notExisting() {
        return stub(false, name -> { created.add(name); return true; });
    }

    private GitState alwaysExisting() {
        return stub(true, name -> { created.add(name); return true; });
    }

    private GitState refusingToCreate() {
        return stub(false, name -> false);
    }

    @FunctionalInterface
    interface BranchCreator { boolean create(String name); }

    private GitState stub(boolean exists, BranchCreator creator) {
        return new GitState(repoRoot) {
            @Override public boolean branchExists(String n) { return exists; }
            @Override public boolean createBranch(String n) { return creator.create(n); }
        };
    }
}
