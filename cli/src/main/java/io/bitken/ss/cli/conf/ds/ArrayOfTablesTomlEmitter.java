package io.bitken.ss.cli.conf.ds;

/**
 * Serializes a {@link StandaloneConfig} to multi-line TOML using {@code [[projects]]}
 * array-of-tables blocks — the one thing Jackson's TOML generator cannot do (it collapses
 * an array of objects onto a single inline line; see plan-90 / jackson #254).
 *
 * <p>Read-side is unaffected: Jackson's TOML <em>parser</em> already understands
 * {@code [[projects]]}, so this emitter only replaces the write path.
 */
class ArrayOfTablesTomlEmitter {

    /** Keys are emitted in this stable, readable order; absent (null) values are skipped. */
    String emit(StandaloneConfig config) {
        StringBuilder sb = new StringBuilder();
        for (StandaloneConfig.ProjectEntry e : config.getProjects()) {
            sb.append("[[projects]]\n");
            appendKey(sb, "remoteUrl", e.getRemoteUrl());
            appendKey(sb, "localPath", e.getLocalPath());
            appendKey(sb, "stateDir", e.getStateDir());
            appendKey(sb, "mode", e.getMode());
            sb.append('\n');
        }
        return sb.toString();
    }

    private static void appendKey(StringBuilder sb, String key, String value) {
        if (value == null) {
            return;
        }
        sb.append(key).append(" = ").append(quote(value)).append('\n');
    }

    /**
     * A literal single-quoted string when the value has no single quote (matches Jackson's
     * prior style for our paths and {@code git@…} URLs); otherwise a double-quoted basic
     * string with the minimal TOML escapes.
     */
    private static String quote(String value) {
        if (value.indexOf('\'') < 0 && !hasControl(value)) {
            return "'" + value + "'";
        }
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < 0x20) {
                return true;
            }
        }
        return false;
    }
}
