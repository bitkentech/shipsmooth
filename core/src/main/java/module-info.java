module io.bitken.ss.core {
    requires jakarta.xml.bind;
    requires java.xml;
    requires org.glassfish.jaxb.runtime;
    requires static dagger;
    requires jakarta.inject;
    requires static java.compiler;

    // Public API consumed by cli (and future targets)
    exports io.bitken.ss;            // generated Build (VERSION, EXPERIMENTAL_BUILD)
    exports io.bitken.ss.conf;
    exports io.bitken.ss.gw;
    exports io.bitken.ss.svc.plan;
    exports io.bitken.ss.jaxb;       // generated from plan-tasks.xsd

    // Reflective access for binding/serialization frameworks
    opens io.bitken.ss.jaxb to jakarta.xml.bind;
}
