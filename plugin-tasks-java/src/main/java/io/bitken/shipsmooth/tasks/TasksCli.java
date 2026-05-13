package io.bitken.shipsmooth.tasks;

import io.bitken.shipsmooth.tasks.commands.*;
import io.bitken.shipsmooth.tasks.commands.IntegrateCommand;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParseResult;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class TasksCli {

    private final CommandLine cmd;
    private final Map<String, Function<ParseResult, Integer>> handlers = new HashMap<>();

    public TasksCli() {
        CommandSpec spec = CommandSpec.wrapWithoutInspection(this);
        spec.name("tasks");
        spec.usageMessage().description("CLI to manage tasks, subagents and ledger for shipsmooth");
        spec.version("0.1.0");

        spec.addSubcommand("init", InitCommand.getSpec());
        spec.addSubcommand("worker-finish", WorkerFinishCommand.getSpec());
        spec.addSubcommand("add-comment", AddCommentCommand.getSpec());
        spec.addSubcommand("add-deviation", AddDeviationCommand.getSpec());
        spec.addSubcommand("claim", ClaimCommand.getSpec());
        spec.addSubcommand("integrate", IntegrateCommand.getSpec());
        spec.addSubcommand("ledger", LedgerCommand.getSpec());
        spec.addSubcommand("ledger-record-commit", LedgerRecordCommitCommand.getSpec());
        spec.addSubcommand("ledger-record-patch-integrated", LedgerRecordPatchIntegratedCommand.getSpec());
        spec.addSubcommand("ledger-resolver-complete", LedgerResolverCompleteCommand.getSpec());
        spec.addSubcommand("ledger-watch", LedgerWatchCommand.getSpec());
        spec.addSubcommand("project-update", ProjectUpdateCommand.getSpec());
        spec.addSubcommand("set-commit", SetCommitCommand.getSpec());
        spec.addSubcommand("show", ShowCommand.getSpec());
        spec.addSubcommand("update-status", UpdateStatusCommand.getSpec());
        spec.addSubcommand("worker-base", WorkerBaseCommand.getSpec());
        spec.addSubcommand("worker-cleanup", WorkerCleanupCommand.getSpec());
        spec.addSubcommand("worker-init", WorkerInitCommand.getSpec());
        spec.addMixin("standardHelpOptions", CommandSpec.forAnnotatedObject(new Object() {
            @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
            boolean help;

            @CommandLine.Option(names = {"-V", "--version"}, versionHelp = true, description = "Print version information and exit.")
            boolean version;
        }));

        handlers.put("init", InitCommand::run);
        handlers.put("worker-finish", WorkerFinishCommand::run);
        handlers.put("add-comment", AddCommentCommand::run);
        handlers.put("add-deviation", AddDeviationCommand::run);
        handlers.put("claim", ClaimCommand::run);
        handlers.put("integrate", IntegrateCommand::run);
        handlers.put("ledger", LedgerCommand::run);
        handlers.put("ledger/list", LedgerCommand.ListCmd::run);
        handlers.put("ledger/verify", LedgerCommand.VerifyCmd::run);
        handlers.put("ledger/read", LedgerCommand.ReadCmd::run);
        handlers.put("ledger-record-commit", LedgerRecordCommitCommand::run);
        handlers.put("ledger-record-patch-integrated", LedgerRecordPatchIntegratedCommand::run);
        handlers.put("ledger-resolver-complete", LedgerResolverCompleteCommand::run);
        handlers.put("ledger-watch", LedgerWatchCommand::run);
        handlers.put("project-update", ProjectUpdateCommand::run);
        handlers.put("set-commit", SetCommitCommand::run);
        handlers.put("show", ShowCommand::run);
        handlers.put("update-status", UpdateStatusCommand::run);
        handlers.put("worker-base", WorkerBaseCommand::run);
        handlers.put("worker-cleanup", WorkerCleanupCommand::run);
        handlers.put("worker-init", WorkerInitCommand::run);

        cmd = new CommandLine(spec);
        cmd.setExecutionStrategy(this::dispatch);
    }

    private int dispatch(ParseResult pr) {
        String key = resolveKey(pr);
        Function<ParseResult, Integer> handler = handlers.get(key);
        if (handler == null) {
            cmd.usage(System.out);
            return 0;
        }
        ParseResult target = deepestSubcommand(pr);
        return handler.apply(target);
    }

    private String resolveKey(ParseResult pr) {
        StringBuilder key = new StringBuilder();
        ParseResult cur = pr;
        while (cur.hasSubcommand()) {
            cur = cur.subcommand();
            if (!key.isEmpty()) key.append('/');
            key.append(cur.commandSpec().name());
        }
        return key.toString();
    }

    private ParseResult deepestSubcommand(ParseResult pr) {
        while (pr.hasSubcommand()) pr = pr.subcommand();
        return pr;
    }

    public int execute(String... args) {
        return cmd.execute(args);
    }

    public static void main(String[] args) {
        int exitCode = new TasksCli().execute(args);
        System.exit(exitCode);
    }
}