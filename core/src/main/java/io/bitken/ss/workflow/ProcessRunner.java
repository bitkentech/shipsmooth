package io.bitken.ss.workflow;

import java.io.File;
import java.io.IOException;

/**
 * Runs external processes for {@link WorkflowServiceImpl}.
 *
 * <p>Extracted from the per-command helpers so the service has one place to
 * shell out to git / verify commands, and tests have one place to inject
 * doubles when they need to simulate IO failures.
 */
public interface ProcessRunner {

    /**
     * Run {@code cmd} in {@code cwd}, throw {@link IOException} if it exits
     * non-zero. Stdout and stderr are merged.
     */
    void run(File cwd, String... cmd) throws IOException, InterruptedException;

    /**
     * Run {@code cmd} in {@code cwd}, return stdout, throw on non-zero exit
     * (with stderr in the exception message). Stderr is captured separately.
     */
    String capture(File cwd, String... cmd) throws IOException, InterruptedException;

    /**
     * Run {@code verifyCmd} via the platform shell in {@code cwd}. Returns
     * {@code null} on success, or the last 50 lines of merged output on
     * non-zero exit. Five-minute timeout.
     */
    String runVerify(File cwd, String verifyCmd) throws IOException, InterruptedException;
}
