module io.bitken.shipsmooth.tasks {
    requires info.picocli;
    requires jakarta.xml.bind;
    requires java.xml;
    requires org.glassfish.jaxb.runtime;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.datatype.jsr310;
    requires static dagger;
    requires jakarta.inject;
    requires static java.compiler;

    opens io.bitken.shipsmooth.tasks to info.picocli;
    opens io.bitken.shipsmooth.tasks.commands to info.picocli;
    opens io.bitken.shipsmooth.tasks.jaxb to jakarta.xml.bind;
    opens io.bitken.shipsmooth.tasks.ledger to com.fasterxml.jackson.databind;
    opens io.bitken.shipsmooth.tasks.integration to com.fasterxml.jackson.databind;
}