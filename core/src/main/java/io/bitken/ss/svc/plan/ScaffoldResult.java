package io.bitken.ss.svc.plan;

import java.nio.file.Path;

/**
 * Outcome of scaffolding a new plan: the three facts the caller needs to render
 * a handoff — the plan id, the branch it was created on, and the stub file
 * written. Carries no I/O; it is the value {@link NewPlan#scaffold} returns on
 * success.
 */
public record ScaffoldResult(int planId, String branchName, Path planFile) {
}
