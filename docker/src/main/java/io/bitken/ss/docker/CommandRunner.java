package io.bitken.ss.docker;

import java.util.List;

/**
 * Seam over external process execution so the docker orchestration can be tested
 * without Docker installed. Production code uses {@link ProcessCommandRunner}.
 */
public interface CommandRunner {

    /** Run {@code argv}, streaming its output, and return the process exit code. */
    int run(List<String> argv);

    /** Run {@code argv} and return its stdout; throws if the process exits non-zero. */
    String capture(List<String> argv);
}
