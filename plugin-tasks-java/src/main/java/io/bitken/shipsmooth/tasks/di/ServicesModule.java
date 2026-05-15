package io.bitken.shipsmooth.tasks.di;

import dagger.Module;
import dagger.Provides;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import io.bitken.shipsmooth.tasks.service.XmlService;
import jakarta.inject.Singleton;

import java.nio.file.Path;

@Module
public class ServicesModule {

    private final Path repoRoot;

    public ServicesModule(Path repoRoot) {
        this.repoRoot = repoRoot;
    }

    @Provides
    @Singleton
    Path provideRepoRoot() {
        return repoRoot;
    }

    @Provides
    @Singleton
    XmlService provideXmlService() {
        return new XmlService();
    }

    @Provides
    @Singleton
    LedgerService provideLedgerService(Path repoRoot) {
        return new LedgerService(repoRoot);
    }
}
