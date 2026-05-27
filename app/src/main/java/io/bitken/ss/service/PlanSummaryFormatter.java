package io.bitken.ss.service;

import io.bitken.ss.jaxb.PlanTasks;
import io.bitken.ss.jaxb.TaskType;
import io.bitken.ss.jaxb.UpdateType;
import io.bitken.ss.jaxb.MetadataType;

/**
 * Renders a {@link PlanTasks} as a human-readable table for the {@code show} command.
 */
public class PlanSummaryFormatter {

    public String format(PlanTasks planTasks) {
        StringBuilder sb = new StringBuilder();
        MetadataType meta = planTasks.getMetadata();
        sb.append(String.format("Plan %d (%s)  status: %s  backlog: %s\n\n",
                planTasks.getPlan(),
                planTasks.getPlanVersion(),
                meta.getStatus().value(),
                meta.getBacklogIssue() != null && !meta.getBacklogIssue().isEmpty() ? meta.getBacklogIssue() : "—"));

        int idWidth = 3;
        int riskWidth = 6;
        int statusWidth = 12;
        int nameWidth = 40;

        String header = String.format("%s  %s  %s  %s  COMMIT",
                pad("ID", idWidth), pad("RISK", riskWidth), pad("STATUS", statusWidth), pad("NAME", nameWidth));
        sb.append(header).append("\n");
        sb.append("-".repeat(header.length())).append("\n");

        for (TaskType t : planTasks.getTasks().getTask()) {
            sb.append(String.format("%s  %s  %s  %s  %s\n",
                    pad(t.getId().toString(), idWidth),
                    pad(t.getRisk(), riskWidth),
                    pad(t.getStatus().value(), statusWidth),
                    pad(t.getName(), nameWidth),
                    t.getCommit() != null && !t.getCommit().isEmpty() ? t.getCommit() : "—"));
        }

        sb.append("\nProject updates:\n");
        for (UpdateType u : planTasks.getProjectUpdates().getUpdate()) {
            String flag = (u.isBlocked() != null && u.isBlocked()) ? " [BLOCKED]" : "";
            sb.append(String.format("  %s%s  %s\n", u.getTimestamp().toString(), flag, u.getMessage()));
        }

        return sb.toString();
    }

    private String pad(String s, int width) {
        if (s == null) s = "";
        if (s.length() >= width) return s.substring(0, width);
        return s + " ".repeat(width - s.length());
    }
}
