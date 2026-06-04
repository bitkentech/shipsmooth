package io.bitken.ss.workflow.integration;

import java.util.*;

public class IntegrationOrder {

    /**
     * Orders tasks for sequential integration:
     * 1. Topological sort by dependsOn (cycle → IllegalArgumentException).
     * 2. Within each topological layer, fewest-overlapping-files-with-later-tasks first.
     * 3. Ties broken by ascending id for determinism.
     */
    public static List<Integer> compute(List<TaskOrderInput> tasks) {
        Map<Integer, TaskOrderInput> byId = new LinkedHashMap<>();
        for (TaskOrderInput t : tasks) byId.put(t.id(), t);

        // Kahn's algorithm
        Map<Integer, Integer> inDegree = new HashMap<>();
        Map<Integer, List<Integer>> dependents = new HashMap<>();
        for (TaskOrderInput t : tasks) {
            inDegree.put(t.id(), t.dependsOn().size());
            dependents.putIfAbsent(t.id(), new ArrayList<>());
            for (int parent : t.dependsOn()) {
                if (!byId.containsKey(parent)) {
                    throw new IllegalArgumentException("Task " + t.id() + " depends on unknown task " + parent);
                }
                dependents.computeIfAbsent(parent, k -> new ArrayList<>()).add(t.id());
            }
        }

        // Topological layers: collect all zero-in-degree nodes per round
        List<Integer> result = new ArrayList<>();
        int remaining = tasks.size();
        while (remaining > 0) {
            List<Integer> layer = new ArrayList<>();
            for (Map.Entry<Integer, Integer> e : inDegree.entrySet()) {
                if (e.getValue() == 0) layer.add(e.getKey());
            }
            if (layer.isEmpty()) {
                throw new IllegalArgumentException("Cycle detected among tasks: " + inDegree.keySet());
            }

            // Compute file sets for all tasks not yet placed (those still in inDegree with value >= 0)
            Set<String> laterFiles = new HashSet<>();
            for (Map.Entry<Integer, Integer> e : inDegree.entrySet()) {
                if (!layer.contains(e.getKey())) {
                    laterFiles.addAll(byId.get(e.getKey()).filesTouched());
                }
            }

            // Sort layer: fewest overlap with later tasks first, then by id
            layer.sort(Comparator
                    .comparingInt((Integer id) -> {
                        Set<String> overlap = new HashSet<>(byId.get(id).filesTouched());
                        overlap.retainAll(laterFiles);
                        return overlap.size();
                    })
                    .thenComparingInt(id -> id));

            for (int id : layer) {
                result.add(id);
                inDegree.remove(id);
                for (int dep : dependents.getOrDefault(id, List.of())) {
                    inDegree.merge(dep, -1, Integer::sum);
                }
                remaining--;
            }
        }

        return result;
    }
}
