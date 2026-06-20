package io.bitken.ss.cli.conf;

public final class StandaloneConfigException extends RuntimeException {
    public StandaloneConfigException(String message, Throwable cause) {
        super(message, cause);
    }
    public StandaloneConfigException(String message) {
        super(message);
    }
}
