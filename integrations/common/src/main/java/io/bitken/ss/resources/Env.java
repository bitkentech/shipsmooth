package io.bitken.ss.resources;

public enum Env {
    DEV, PROD;

    public static Env from(String prop) {
        return "dev".equals(prop) ? DEV : PROD;
    }

    public boolean isDev() {
        return this == DEV;
    }

    public String suffix() {
        return isDev() ? "-dev" : "";
    }

    public String decorate(String base) {
        return base + suffix();
    }
}
