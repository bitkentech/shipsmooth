package io.bitken.ss.svc.plan;

import io.bitken.ss.gw.TaskStore;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Markdown slicing utilities for plan narrative files.
 */
public class PlanMarkdown {

    private final TaskStore xmlService;

    public PlanMarkdown(TaskStore xmlService) {
        this.xmlService = xmlService;
    }

    /**
     * Returns the section of {@code plan-{planId}.md} starting at "### Task {taskId}:"
     * up to the next task heading. Returns empty string if the file or section is absent.
     */
    public String sliceTaskSection(int planId, int taskId) {
        try {
            File planFile = xmlService.planMarkdownFile(planId);
            if (!planFile.exists()) return "";
            String content = Files.readString(planFile.toPath());
            String marker = "### Task " + taskId + ":";
            int start = content.indexOf(marker);
            if (start < 0) return "";
            int next = content.indexOf("### Task ", start + marker.length());
            return next > 0 ? content.substring(start, next).trim() : content.substring(start).trim();
        } catch (IOException e) {
            return "";
        }
    }
}
