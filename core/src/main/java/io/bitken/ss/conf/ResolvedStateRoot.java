package io.bitken.ss.conf;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Capability token proving a shipsmooth state root has been validated.
 *
 * <p>Parse-don't-validate: holding a {@code ResolvedStateRoot} <em>is</em> the proof that
 * its path points at an accessible directory. Validation happens exactly once, in the
 * {@link #of(Path)} smart constructor — the only way to obtain an instance. Consumers (the
 * data locator) therefore take the token instead of a bare {@code Path} and never re-check
 * the filesystem: the bad case is excluded by the type, not by a runtime guard.
 *
 * <p>The token lives in {@code core} on purpose: it is the shared handoff contract between
 * whoever resolved the state root (today the CLI's {@code ProjectDataStoreResolver}) and the
 * reusable data layer that serves files from it.
 */
public final class ResolvedStateRoot {

    private final Path path;

    private ResolvedStateRoot(Path path) {
        this.path = path;
    }

    /**
     * Validate {@code stateRoot} and mint a token. This is the single point where an
     * inaccessible state root is rejected.
     *
     * @throws InaccessibleRootException if the path is null, missing, or not a directory.
     */
    public static ResolvedStateRoot of(Path stateRoot) {
        if (stateRoot == null) {
            throw new InaccessibleRootException("state", stateRoot, "path is null");
        }
        if (!Files.exists(stateRoot)) {
            throw new InaccessibleRootException("state", stateRoot, "does not exist");
        }
        if (!Files.isDirectory(stateRoot)) {
            throw new InaccessibleRootException("state", stateRoot, "is not a directory");
        }
        return new ResolvedStateRoot(stateRoot);
    }

    /** The validated state-root directory. */
    public Path path() {
        return path;
    }
}
