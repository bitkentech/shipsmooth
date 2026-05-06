package io.bitken.shipsmooth.tasks.integration;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Production SubagentRunner: prints a JSON spawn-payload to stdout, then blocks
 * reading a {"action":"continue"} line from stdin. The Lead Agent performs
 * the Agent tool call and writes the reply.
 */
public class StdinStdoutSubagentRunner implements SubagentRunner {

    /** How long to wait for the Lead Agent to write {"action":"continue"} before aborting. */
    static final long CONTINUE_TIMEOUT_MINUTES = 30;

    private final String worktreePath;
    private final ObjectMapper mapper = new ObjectMapper();
    private final BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
    private final PrintStream stdout = System.out;

    public StdinStdoutSubagentRunner(String worktreePath) {
        this.worktreePath = worktreePath;
    }

    @Override
    public void run(String prompt) throws Exception {
        String payload = mapper.writeValueAsString(Map.of(
                "action", "spawn-resolver",
                "prompt", prompt,
                "worktree", worktreePath));
        stdout.println(payload);
        stdout.flush();

        // Block until Lead Agent writes {"action":"continue"}, with a timeout.
        // If no response arrives within CONTINUE_TIMEOUT_MINUTES the Lead Agent has missed
        // the spawn-resolver line — fail loudly rather than hanging forever.
        ExecutorService exec = Executors.newSingleThreadExecutor();
        Future<String> future = exec.submit(() -> {
            String line;
            while ((line = stdin.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                return line;
            }
            return null;
        });
        exec.shutdown();

        String line;
        try {
            line = future.get(CONTINUE_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException(
                    "integrate: timed out waiting for Lead Agent after " + CONTINUE_TIMEOUT_MINUTES
                    + " minutes. The spawn-resolver JSON line may have been missed on stdout. "
                    + "Write {\"action\":\"continue\"} to stdin to resume, or re-run integrate.");
        }

        if (line == null) {
            throw new IllegalStateException("integrate: stdin closed before Lead Agent replied.");
        }
        Map<?, ?> reply = mapper.readValue(line, Map.class);
        if (!"continue".equals(reply.get("action"))) {
            System.err.println("integrate: unexpected reply from harness: " + line);
        }
    }
}
