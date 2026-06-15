package io.bitken.ss.svc.plan;

/**
 * Thrown by {@link NewPlan#scaffold} when a new plan cannot be scaffolded —
 * the branch already exists, or git refused to create it. Carries a
 * human-readable message for the caller to surface.
 */
public class ScaffoldException extends Exception {

    public ScaffoldException(String message) {
        super(message);
    }
}
