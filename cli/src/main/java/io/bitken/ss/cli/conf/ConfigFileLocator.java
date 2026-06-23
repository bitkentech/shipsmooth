package io.bitken.ss.cli.conf;

import java.nio.file.Path;

/**
 * Locates the shipsmooth config file ({@code shipsmooth.toml}). Pluggable so tests
 * can point the resolver at a fixed path; {@link DefaultConfigFileLocator} holds the
 * real per-platform location logic.
 */
@FunctionalInterface
public interface ConfigFileLocator {
    Path locate();
}