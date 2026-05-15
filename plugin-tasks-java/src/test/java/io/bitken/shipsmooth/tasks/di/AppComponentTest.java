package io.bitken.shipsmooth.tasks.di;

import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import io.bitken.shipsmooth.tasks.service.XmlService;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

public class AppComponentTest {

    @Test
    public void buildsComponentAndProvidesServices() {
        AppComponent app = DaggerAppComponent.builder()
                .servicesModule(new ServicesModule(Paths.get(".")))
                .build();

        XmlService xml = app.xmlService();
        LedgerService ledger = app.ledgerService();

        assertNotNull(xml);
        assertNotNull(ledger);
    }

    @Test
    public void servicesAreSingletonsWithinComponent() {
        AppComponent app = DaggerAppComponent.builder()
                .servicesModule(new ServicesModule(Paths.get(".")))
                .build();

        assertSame(app.xmlService(), app.xmlService());
        assertSame(app.ledgerService(), app.ledgerService());
    }
}
