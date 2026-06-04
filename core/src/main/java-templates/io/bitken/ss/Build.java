package io.bitken.ss;

public final class Build {
    public static final boolean EXPERIMENTAL_BUILD = ${experimental.enabled};
    public static final String VERSION = "${project.version}";
    private Build() {}
}