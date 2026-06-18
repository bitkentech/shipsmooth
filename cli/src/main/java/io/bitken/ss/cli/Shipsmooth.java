package io.bitken.ss.cli;

import io.bitken.ss.cli.conf.ExperimentalModeParser;
import io.bitken.ss.conf.AppComponents;
import io.bitken.ss.conf.DaggerAppComponents;
import io.bitken.ss.conf.ServicesModule;
import picocli.CommandLine;

import java.nio.file.Paths;

/**
 * One-shot shipsmooth CLI: bound to a fixed argv at construction and run once
 * via {@link #execute()}.
 */
public class Shipsmooth {

    private final CommandTree commandTree;
    private final CommandLine cmd;
    private final String[] args;

    public Shipsmooth(AppComponents app, String[] args) {
        this.args = args;
        this.commandTree = new CommandTree(app);
        this.cmd = commandTree.commandLine();
    }

    public int execute() {
        return cmd.execute(args);
    }

    public static void main(String[] args) {
        AppComponents app = DaggerAppComponents.builder()
            .servicesModule(new ServicesModule(
                new RepoRoot(Paths.get(".")).path(),
                ExperimentalModeParser.fromArgs(args)))
            .build();

        int exitCode = new Shipsmooth(app, args).execute();
        System.exit(exitCode);
    }
}
