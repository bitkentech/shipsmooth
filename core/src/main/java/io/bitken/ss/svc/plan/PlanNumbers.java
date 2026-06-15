package io.bitken.ss.svc.plan;

import io.bitken.ss.conf.ShipsmoothDataLocator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads the plans directory to answer "what is the next plan id".
 *
 * <p>The one place that performs the filesystem read; it derives both the plans
 * directory and the {@code plan-N.md} name scheme from {@link
 * ShipsmoothDataLocator} so the naming lives in a single source of truth.
 */
public final class PlanNumbers {

    private final Path plansDir;
    private final Pattern planFile;

    public PlanNumbers(ShipsmoothDataLocator locator) {
        this.plansDir = locator.plansDir();
        this.planFile = locator.planMarkdownPattern();
    }

    /** The next plan id: highest existing {@code plan-N.md} + 1, or 1 if none. */
    public int next() throws IOException {
        return highestExisting() + 1;
    }

    private int highestExisting() throws IOException {
        if (!Files.isDirectory(plansDir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(plansDir)) {
            return files.map(p -> p.getFileName().toString())
                        .map(planFile::matcher)
                        .filter(Matcher::matches)
                        .mapToInt(m -> Integer.parseInt(m.group(1)))
                        .max()
                        .orElse(0);
        }
    }
}
