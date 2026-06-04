module io.bitken.ss.core {
    requires jakarta.xml.bind;
    requires java.xml;
    requires org.glassfish.jaxb.runtime;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.datatype.jsr310;
    requires static dagger;
    requires jakarta.inject;
    requires static java.compiler;

    // Public API consumed by cli (and future targets)
    exports io.bitken.ss;            // generated Build (VERSION, EXPERIMENTAL_BUILD)
    exports io.bitken.ss.conf;
    exports io.bitken.ss.git;
    exports io.bitken.ss.gw;
    exports io.bitken.ss.ledger;
    exports io.bitken.ss.svc.plan;
    exports io.bitken.ss.workflow;
    exports io.bitken.ss.workflow.integration;
    exports io.bitken.ss.jaxb;       // generated from plan-tasks.xsd

    // Reflective access for binding/serialization frameworks
    opens io.bitken.ss.jaxb to jakarta.xml.bind;
    opens io.bitken.ss.ledger to com.fasterxml.jackson.databind;
    opens io.bitken.ss.workflow.integration to com.fasterxml.jackson.databind;
}
