package io.bitken.ss.workflow;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** Production {@link ProcessRunner} that actually shells out. */
public class DefaultProcessRunner implements ProcessRunner {

    @Override
    public void run(File cwd, String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).directory(cwd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor(60, TimeUnit.SECONDS);
        if (p.exitValue() != 0) {
            throw new IOException("Command failed: " + String.join(" ", cmd) + "\n" + out);
        }
    }

    @Override
    public String capture(File cwd, String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).directory(cwd).redirectErrorStream(false).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor(60, TimeUnit.SECONDS);
        if (p.exitValue() != 0) {
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new IOException("Command failed: " + String.join(" ", cmd) + "\n" + err);
        }
        return out;
    }

    @Override
    public String runVerify(File cwd, String verifyCmd) throws IOException, InterruptedException {
        String os = System.getProperty("os.name", "").toLowerCase();
        String[] cmd = os.contains("win")
                ? new String[]{"cmd", "/c", verifyCmd}
                : new String[]{"sh", "-c", verifyCmd};
        Process p = new ProcessBuilder(cmd)
                .directory(cwd)
                .redirectErrorStream(true)
                .start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor(5, TimeUnit.MINUTES);
        if (p.exitValue() == 0) return null;
        String[] lines = output.split("\n");
        int start = Math.max(0, lines.length - 50);
        return String.join("\n", Arrays.copyOfRange(lines, start, lines.length));
    }
}
