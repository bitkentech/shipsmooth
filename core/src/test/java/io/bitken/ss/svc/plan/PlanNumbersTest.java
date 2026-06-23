package io.bitken.ss.svc.plan;

import io.bitken.ss.conf.ShipsmoothDataLocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PlanNumbersTest {

    @TempDir
    Path repoRoot;

    private PlanNumbers planNumbers() {
        return new PlanNumbers(new ShipsmoothDataLocator(repoRoot));
    }

    @Test
    void returnsOneWhenPlansDirAbsent() throws IOException {
        assertEquals(1, planNumbers().next());
    }

    @Test
    void returnsOneWhenPlansDirEmpty() throws IOException {
        Files.createDirectories(repoRoot.resolve(".shipsmooth/plans"));
        assertEquals(1, planNumbers().next());
    }

    @Test
    void returnsMaxPlusOne() throws IOException {
        writePlans("plan-1.md", "plan-2.md", "plan-3.md");
        assertEquals(4, planNumbers().next());
    }

    @Test
    void usesMaxNotCountAcrossGaps() throws IOException {
        writePlans("plan-1.md", "plan-5.md");
        assertEquals(6, planNumbers().next());
    }

    @Test
    void ignoresNonPlanAndTasksFiles() throws IOException {
        writePlans("plan-2.md", "plan-2-tasks.xml", "README.md", "notes.txt");
        assertEquals(3, planNumbers().next());
    }

    private void writePlans(String... names) throws IOException {
        Path plansDir = repoRoot.resolve(".shipsmooth/plans");
        Files.createDirectories(plansDir);
        for (String name : names) {
            Files.writeString(plansDir.resolve(name), "x");
        }
    }
}
