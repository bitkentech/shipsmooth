package io.bitken.shipsmooth.tasks.integration;

import java.util.List;
import java.util.Set;

public record TaskOrderInput(int id, List<Integer> dependsOn, Set<String> filesTouched) {}
