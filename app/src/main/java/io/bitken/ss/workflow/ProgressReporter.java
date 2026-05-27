package io.bitken.ss.workflow;

/**
 * Presenter seam for the workflow use cases.
 *
 * <p>The use cases speak domain verbs ("task integrated", "merge order:"). The
 * adapter chooses how to surface them — console prose now, structured JSON or
 * GUI events later. Keeps stdout/stderr formatting out of the use-case layer
 * and lets tests assert on calls instead of captured streams.
 */
public interface ProgressReporter {
    /** Informational progress event (typically prints to stdout in the console adapter). */
    void info(String message);

    /** Warning or non-fatal error event (typically prints to stderr in the console adapter). */
    void warn(String message);
}
