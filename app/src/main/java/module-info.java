module io.bitken.ss {
    requires info.picocli;
    requires jakarta.xml.bind;
    requires java.xml;
    requires org.glassfish.jaxb.runtime;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.datatype.jsr310;
    requires static dagger;
    requires jakarta.inject;
    requires static java.compiler;

    opens io.bitken.ss.cli to info.picocli;
    opens io.bitken.ss.jaxb to jakarta.xml.bind;
    opens io.bitken.ss.ledger to com.fasterxml.jackson.databind;
    opens io.bitken.ss.workflow.integration to com.fasterxml.jackson.databind;
}