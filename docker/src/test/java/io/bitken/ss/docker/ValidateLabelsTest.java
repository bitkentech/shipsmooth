package io.bitken.ss.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bitken.ss.docker.ResolveVersions.Versions;
import java.util.List;
import org.junit.jupiter.api.Test;

class ValidateLabelsTest {

    private static final String INSPECT_JSON = """
            [
              {
                "Config": {
                  "Labels": {
                    "org.opencontainers.image.version": "claude-2.1.236-ss-0.3.36",
                    "io.bitken.ss.claude-code.version": "2.1.236",
                    "io.bitken.ss.shipsmooth.version": "0.3.36"
                  }
                }
              }
            ]
            """;

    @Test
    void parsesComponentVersionsFromInspectJson() {
        Versions v = ValidateLabels.labelsFromInspect(INSPECT_JSON);
        assertEquals("2.1.236", v.claudeCode());
        assertEquals("0.3.36", v.shipsmooth());
    }

    @Test
    void parsesComponentVersionsFromOverviewMarkdown() {
        String overview = BuildAndPushImage.renderOverview("bitkentech/shipsmooth-claude", new Versions("2.1.236", "0.3.36"));
        Versions v = ValidateLabels.versionsFromOverview(overview);
        assertEquals("2.1.236", v.claudeCode());
        assertEquals("0.3.36", v.shipsmooth());
    }

    @Test
    void parsesClaudeCliVersionOutput() {
        assertEquals("2.1.236", ValidateLabels.parseClaudeVersion("2.1.236 (Claude Code)"));
        assertEquals("2.1.252", ValidateLabels.parseClaudeVersion("claude-code/2.1.252\n"));
    }

    @Test
    void matchingVersionsProduceNoMismatches() {
        Versions labels = new Versions("2.1.236", "0.3.36");
        assertIterableEquals(List.of(), ValidateLabels.mismatches(labels, labels, "Overview"));
    }

    @Test
    void divergentVersionsAreReportedPerComponent() {
        Versions labels = new Versions("2.1.236", "0.3.36");
        Versions overview = new Versions("2.1.999", "0.3.36");
        List<String> problems = ValidateLabels.mismatches(labels, overview, "Overview");
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("claude-code"), problems.get(0));
        assertTrue(problems.get(0).contains("2.1.236"), problems.get(0));
        assertTrue(problems.get(0).contains("2.1.999"), problems.get(0));
    }

    @Test
    void repoIsDerivedFromAnImageRef() {
        assertEquals("bitkentech/shipsmooth-claude", ValidateLabels.repoOf("bitkentech/shipsmooth-claude:latest"));
        assertEquals("bitkentech/shipsmooth-claude", ValidateLabels.repoOf("bitkentech/shipsmooth-claude"));
    }
}
