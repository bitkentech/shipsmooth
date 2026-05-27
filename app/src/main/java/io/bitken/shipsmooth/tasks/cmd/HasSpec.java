package io.bitken.shipsmooth.tasks.cmd;

import picocli.CommandLine.Model.CommandSpec;

public interface HasSpec {
    CommandSpec getSpec();
}