package io.bitken.ss.git;

import java.util.List;

public record MergeResult(boolean clean, List<String> conflictedFiles) {

    public static MergeResult success() {
        return new MergeResult(true, List.of());
    }

    public static MergeResult conflict(List<String> conflictedFiles) {
        return new MergeResult(false, conflictedFiles);
    }
}
