package io.bitken.ss.cli.conf;

import java.nio.file.Path;

/** Result of standalone config resolution. */
public sealed interface ResolvedMode {
    record InRepo()                      implements ResolvedMode {}
    record Standalone(Path stateDir)     implements ResolvedMode {}
}
