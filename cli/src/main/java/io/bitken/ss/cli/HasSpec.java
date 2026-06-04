package io.bitken.ss.cli;

import picocli.CommandLine.Model.CommandSpec;

public interface HasSpec {
    CommandSpec getSpec();
}