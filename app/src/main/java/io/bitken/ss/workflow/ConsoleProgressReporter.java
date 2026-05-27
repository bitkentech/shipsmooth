package io.bitken.ss.workflow;

/** Default reporter: info → stdout, warn → stderr. */
public class ConsoleProgressReporter implements ProgressReporter {
    @Override public void info(String message) { System.out.println(message); }
    @Override public void warn(String message) { System.err.println(message); }
}
