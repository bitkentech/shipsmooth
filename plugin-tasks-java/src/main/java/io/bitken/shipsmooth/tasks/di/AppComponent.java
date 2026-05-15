package io.bitken.shipsmooth.tasks.di;

import dagger.Component;
import io.bitken.shipsmooth.tasks.commands.AddCommentCommand;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import io.bitken.shipsmooth.tasks.service.XmlService;
import jakarta.inject.Singleton;

@Singleton
@Component(modules = ServicesModule.class)
public interface AppComponent {
    XmlService xmlService();
    LedgerService ledgerService();
    AddCommentCommand addCommentCommand();
}
