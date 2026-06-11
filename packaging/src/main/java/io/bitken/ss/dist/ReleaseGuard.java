package io.bitken.ss.dist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Release-time prod correctness guard (plan-75 Task 5). Fails the release loudly if a
 * packaged artifact is wrong — the check that would have caught both 0.3.17 defects:
 * a binary stamped with the previous version, and one that still exposes the
 * experimental surface in {@code --help}.
 *
 * <p>Two layers. The <b>executable</b> check execs the staged linux-x64 launcher (the
 * only platform runnable on the release host) and inspects its real {@code --version}
 * / {@code --help}. The <b>source-of-truth</b> check reads the generated
 * {@code Build.java} — a single shared source baked into every platform image — so the
 * non-runnable platforms (darwin, windows) are covered too. The assertion logic is
 * pure (operates on captured strings), so it is unit-tested without a binary.
 */
public final class ReleaseGuard {

    /** Experimental surface that a prod {@code --help} must not list. */
    private static final List<String> EXPERIMENTAL_TOKENS =
        List.of("--enable-experimental", "ledger", "worker", "claim", "integrate");

    private static final Pattern EXPERIMENTAL_BUILD_FIELD =
        Pattern.compile("EXPERIMENTAL_BUILD\\s*=\\s*(true|false)");
    private static final Pattern VERSION_FIELD =
        Pattern.compile("VERSION\\s*=\\s*\"([^\"]*)\"");

    private ReleaseGuard() {}

    // ---- pure assertions (unit-tested) ----

    /**
     * Assert the launcher's {@code --version} equals {@code expectedVersion} and its
     * {@code --help} lists no experimental surface.
     */
    static void assertLauncherOutputIsProd(String versionOutput, String helpOutput, String expectedVersion) {
        String actual = versionOutput.strip();
        if (!actual.equals(expectedVersion)) {
            throw new IllegalStateException(
                "Release guard: launcher --version is '" + actual + "' but the release version is '"
                    + expectedVersion + "'. The packaged binary was built from stale constants.");
        }
        for (String token : EXPERIMENTAL_TOKENS) {
            if (helpLists(helpOutput, token)) {
                throw new IllegalStateException(
                    "Release guard: prod --help exposes experimental surface '" + token
                        + "'. A prod build must hide it.");
            }
        }
    }

    /**
     * Assert the disassembled {@code io.bitken.ss.Build} constants have
     * {@code EXPERIMENTAL_BUILD = false} and {@code VERSION = expectedVersion}. The
     * input is {@code javap -constants} output read from the {@code Build.class} that
     * is actually baked into a shipped jlink image (see {@link #guardImageConstants}),
     * so this verifies the real artifact, per platform — not an intermediate source
     * file that a later compile could have changed.
     */
    static void assertBuildConstantsAreProd(String javapOutput, String expectedVersion) {
        Matcher exp = EXPERIMENTAL_BUILD_FIELD.matcher(javapOutput);
        if (!exp.find()) {
            throw new IllegalStateException("Release guard: no EXPERIMENTAL_BUILD constant found in Build.class.");
        }
        if (!"false".equals(exp.group(1))) {
            throw new IllegalStateException(
                "Release guard: image Build.EXPERIMENTAL_BUILD = true; a prod build must bake false.");
        }
        Matcher ver = VERSION_FIELD.matcher(javapOutput);
        if (!ver.find()) {
            throw new IllegalStateException("Release guard: no VERSION constant found in Build.class.");
        }
        if (!ver.group(1).equals(expectedVersion)) {
            throw new IllegalStateException(
                "Release guard: image Build.VERSION is '" + ver.group(1) + "' but the release version is '"
                    + expectedVersion + "'.");
        }
    }

    /**
     * Whether a {@code --help} usage block actually lists {@code token} (as an option
     * or a subcommand line), not merely mentions it in prose. Mirrors the CLI tests'
     * line-based check so a stray word in a description doesn't trip the guard.
     */
    private static boolean helpLists(String helpOutput, String token) {
        if (token.startsWith("--")) {
            return helpOutput.contains(token);
        }
        for (String line : helpOutput.split("\\R")) {
            String t = line.strip();
            if (t.startsWith(token + " ") || t.equals(token)) return true;
        }
        return false;
    }

    // ---- exec/IO wrappers (driven on the real release path) ----

    /**
     * Verify the {@code io.bitken.ss.Build} constants baked into a shipped jlink image
     * — the actual artifact, not an intermediate source file. The class lives packed in
     * the image's {@code lib/modules}; {@code jimage} extracts it and {@code javap}
     * disassembles its constant values. Both are host JDK tools that read the image as
     * data, so this verifies EVERY platform image (including darwin/windows, which can't
     * be executed on the release host) from its real shipped bytes.
     *
     * @param jdkHome host JDK with {@code bin/jimage} + {@code bin/javap}
     */
    static void guardImageConstants(Path jdkHome, Path imageDir, String expectedVersion)
            throws IOException, InterruptedException {
        Path modules = imageDir.resolve("lib/modules");
        if (!Files.exists(modules)) {
            throw new IllegalStateException("Release guard: no lib/modules in image: " + imageDir);
        }
        Path tmp = Files.createTempDirectory("ss-release-guard-" + UUID.randomUUID());
        try {
            PublishRelease.runCommand(List.of(
                jdkHome.resolve("bin/jimage").toString(), "extract",
                "--include", "**/io/bitken/ss/Build.class",
                "--dir", tmp.toString(),
                modules.toString()), imageDir);
            // jimage lays the class under <tmp>/<module-name>/io/bitken/ss/Build.class;
            // point javap at the module-name dir as the classpath root.
            Path classpathRoot = tmp.resolve("io.bitken.ss.core");
            String javap = PublishRelease.runCommand(List.of(
                jdkHome.resolve("bin/javap").toString(), "-p", "-constants",
                "-cp", classpathRoot.toString(), "io.bitken.ss.Build"), imageDir);
            assertBuildConstantsAreProd(javap, expectedVersion);
        } finally {
            deleteRecursive(tmp);
        }
    }

    /** Exec the staged linux-x64 launcher and run the pure launcher guard against it. */
    static void guardLinuxLauncher(Path linuxImageDir, String expectedVersion)
            throws IOException, InterruptedException {
        // Resolve to an ABSOLUTE launcher path: runCommand sets the working directory,
        // so a relative path would be resolved against it and fail to exec.
        Path launcher = linuxImageDir.resolve("bin/shipsmooth").toAbsolutePath();
        if (!Files.isExecutable(launcher)) {
            throw new IllegalStateException("Release guard: staged linux-x64 launcher not found/executable: " + launcher);
        }
        Path workDir = launcher.getParent();
        String version = PublishRelease.runCommand(List.of(launcher.toString(), "--version"), workDir);
        String help = PublishRelease.runCommand(List.of(launcher.toString(), "--help"), workDir);
        assertLauncherOutputIsProd(version, help, expectedVersion);
    }

    private static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) { }
            });
        }
    }
}
