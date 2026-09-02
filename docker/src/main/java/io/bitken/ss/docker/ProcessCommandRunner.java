package io.bitken.ss.docker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Runs commands as real subprocesses. */
public final class ProcessCommandRunner implements CommandRunner {

    @Override
    public int run(List<String> argv) {
        try {
            System.err.println("+ " + String.join(" ", argv));
            Process process = new ProcessBuilder(argv).inheritIO().start();
            return process.waitFor();
        } catch (IOException e) {
            throw new IllegalStateException("could not start: " + String.join(" ", argv), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running: " + String.join(" ", argv), e);
        }
    }

    @Override
    public String capture(List<String> argv) {
        try {
            Process process = new ProcessBuilder(argv)
                    .redirectErrorStream(true)
                    .start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = process.waitFor();
            if (code != 0) {
                throw new IllegalStateException(
                        "exit " + code + " from " + String.join(" ", argv) + ":\n" + out);
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("could not start: " + String.join(" ", argv), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running: " + String.join(" ", argv), e);
        }
    }
}
