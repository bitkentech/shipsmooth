package io.bitken.shipsmooth.tasks.commands;

import picocli.CommandLine.Model.CommandSpec;

public interface HasSpec {
    CommandSpec getSpec();
}