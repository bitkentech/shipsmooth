module io.bitken.ss.cli {
    requires io.bitken.ss.core;
    requires info.picocli;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.datatype.jsr310;
    requires com.fasterxml.jackson.dataformat.toml;

    opens io.bitken.ss.cli to info.picocli;
    opens io.bitken.ss.cli.ledger to info.picocli;
    opens io.bitken.ss.cli.worker to info.picocli;
    opens io.bitken.ss.cli.task to info.picocli;
    opens io.bitken.ss.cli.plan to info.picocli;
}
