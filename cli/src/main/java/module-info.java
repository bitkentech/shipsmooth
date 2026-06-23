module io.bitken.ss.cli {
    requires io.bitken.ss.core;
    requires info.picocli;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.datatype.jsr310;
    requires com.fasterxml.jackson.dataformat.toml;

    opens io.bitken.ss.cli to info.picocli;
    opens io.bitken.ss.cli.task to info.picocli;
    opens io.bitken.ss.cli.plan to info.picocli;

    // Jackson (TomlMapper) reflects over StandaloneConfig/ProjectEntry to read and write
    // ~/.config/shipsmooth/shipsmooth.toml. Without this open, store init/info throw
    // InaccessibleObjectException in the modular jlink runtime (caught only by the
    // jlinkSmokeStore smoke test, not classpath unit tests). See plan-87.
    opens io.bitken.ss.cli.conf.ds to com.fasterxml.jackson.databind;
}
