package io.bitken.ss.conf;

import java.nio.file.Path;

/**
 * Thrown when a root passed to {@link ShipsmoothDataLocator} (the project repo
 * root or the shipsmooth state root) does not point at an accessible directory.
 * Unchecked so it surfaces as a fail-fast startup error without forcing
 * {@code throws} onto every construction site.
 */
public class InaccessibleRootException extends RuntimeException {

    public InaccessibleRootException(String role, Path path, String reason) {
        super(role + " root " + path + " is not accessible: " + reason);
    }
}
