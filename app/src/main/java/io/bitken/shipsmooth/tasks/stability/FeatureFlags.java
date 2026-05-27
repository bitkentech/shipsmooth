package io.bitken.shipsmooth.tasks.stability;

public interface FeatureFlags {
    default boolean isExperimental() {
        return false;
    }
}