package io.bitken.shipsmooth.tasks.integration;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.Map;

/**
 * Production SubagentRunner: prints a JSON spawn-payload to stdout, then blocks
 * reading a {"action":"continue"} line from stdin. The Lead Agent performs
 * the Agent tool call and writes the reply.
 */
public class StdinStdoutSubagentRunner implements SubagentRunner {

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

        // Block until Lead Agent writes {"action":"continue"}
        String line;
        while ((line = stdin.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            Map<?, ?> reply = mapper.readValue(line, Map.class);
            if ("continue".equals(reply.get("action"))) break;
            // Any other action is unexpected — surface and continue
            System.err.println("integrate: unexpected reply from harness: " + line);
        }
    }
}
