package io.bitken.ss.conf;

public interface FeatureFlags {
    default boolean isExperimental() {
        return false;
    }
}