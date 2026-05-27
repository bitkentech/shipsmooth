package io.bitken.ss.workflow.integration;

import java.util.List;
import java.util.Set;

public record TaskOrderInput(int id, List<Integer> dependsOn, Set<String> filesTouched) {}
