package io.bitken.ss.dist;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PublishBetaReleaseTest {

    @TempDir
    Path tmp;

    /** Records every command; answers the queries a release asks with canned output. */
    static final class FakeRunner implements PublishBetaRelease.CommandRunner {
        final List<String> commands = new ArrayList<>();
        String binaryVersion = "0.1.0-beta";
        String localTags = "";
        String remoteTags = "";
        String remoteHeads = "";
        String status = "";
        String failOn = null;
        String releaseView = "{\"isDraft\":false,\"isPrerelease\":true,\"assets\":["
            + "{\"name\":\"shipsmooth-beta-0.1.0-beta-linux-x64.zip\"},"
            + "{\"name\":\"shipsmooth-beta-0.1.0-beta-darwin-x64.zip\"},"
            + "{\"name\":\"shipsmooth-beta-0.1.0-beta-darwin-arm64.zip\"}]}";

        @Override
        public String run(List<String> cmd, Path dir) throws IOException {
            String line = String.join(" ", cmd);
            commands.add(line);
            if (failOn != null && line.startsWith(failOn)) throw new IOException("Command failed: " + line);
            if (line.endsWith("--version")) return binaryVersion + "\n";
            if (line.startsWith("git status")) return status;
            if (line.startsWith("git tag -l")) return localTags;
            if (line.startsWith("git ls-remote --tags")) return remoteTags;
            if (line.startsWith("git ls-remote --heads")) return remoteHeads;
            if (line.startsWith("git rev-parse --short HEAD")) return "abc1234\n";
            if (line.startsWith("gh release view")) return releaseView;
            return "";
        }

        int indexOf(String prefix) {
            for (int i = 0; i < commands.size(); i++) if (commands.get(i).startsWith(prefix)) return i;
            return -1;
        }

        boolean anyOutward() {
            return commands.stream().anyMatch(c -> c.startsWith("git push") || c.startsWith("gh release create"));
        }
    }

    private PublishBetaRelease.Config config(boolean enabled) throws Exception {
        Path binDir = tmp.resolve("beta-bin");
        for (String platform : PackageBeta.PLATFORMS) {
            Files.writeString(Files.createDirectories(binDir.resolve(platform)).resolve("shipsmooth-beta"), platform);
        }
        Path payload = Files.createDirectories(tmp.resolve("build-claude-beta/.claude-plugin"));
        Files.writeString(payload.resolve("plugin.json"), "{\"name\":\"shipsmooth-beta\"}");
        return new PublishBetaRelease.Config(enabled, "0.1.0-beta", tmp.resolve("repo"), binDir,
            tmp.resolve("build-claude-beta"), tmp.resolve("dist"), tmp.resolve("worktree"));
    }

    @Test
    void publishesAPrereleaseFromTheReleasesBetaBranch_withProvenanceAndAllAssets() throws Exception {
        FakeRunner runner = new FakeRunner();

        new PublishBetaRelease(config(true), runner).run();

        int tagCheck = runner.indexOf("git ls-remote --tags origin v0.1.0-beta");
        int versionCheck = runner.indexOf(tmp.resolve("beta-bin/linux-x64/shipsmooth-beta") + " --version");
        int push = runner.indexOf("git push origin releases-beta v0.1.0-beta");
        int create = runner.indexOf("gh release create v0.1.0-beta");
        assertTrue(tagCheck >= 0 && versionCheck >= 0 && push >= 0 && create >= 0, runner.commands.toString());
        assertTrue(tagCheck < push && versionCheck < push && push < create, "all checks precede anything outward");

        String createLine = runner.commands.get(create);
        assertTrue(createLine.contains("--target releases-beta"), createLine);
        assertTrue(createLine.contains("--prerelease"), createLine);
        assertTrue(createLine.contains("--latest=false"), "a beta must never become the repo's latest release");
        assertTrue(createLine.contains("--notes Release v0.1.0-beta (main: abc1234)"), createLine);
        for (String platform : PackageBeta.PLATFORMS) {
            assertTrue(createLine.contains("shipsmooth-beta-0.1.0-beta-" + platform + ".zip"), platform);
        }
        assertTrue(Files.isRegularFile(tmp.resolve("worktree/dist/.claude-plugin/plugin.json")),
            "the assembled payload is staged at dist/ on the releases-beta worktree");
        assertTrue(runner.indexOf("gh release view v0.1.0-beta") > create, "confirmed by artifact after publishing");
    }

    @ParameterizedTest
    @ValueSource(strings = {"gate-off", "missing-binary", "missing-payload", "dirty-tree",
        "local-tag-exists", "remote-tag-exists", "version-mismatch"})
    void refusesBeforeAnythingOutward(String reason) throws Exception {
        FakeRunner runner = new FakeRunner();
        PublishBetaRelease.Config config = config(!reason.equals("gate-off"));
        switch (reason) {
            case "missing-binary" -> Files.delete(PackageBeta.binary(config.binDir(), "darwin-arm64"));
            case "missing-payload" -> deleteRecursively(config.payloadDir());
            case "dirty-tree" -> runner.status = " M Cargo.toml\n";
            case "local-tag-exists" -> runner.localTags = "v0.1.0-beta\n";
            case "remote-tag-exists" -> runner.remoteTags = "abc\trefs/tags/v0.1.0-beta\n";
            case "version-mismatch" -> runner.binaryVersion = "0.0.9-beta";
            default -> { }
        }

        assertThrows(IllegalStateException.class, () -> new PublishBetaRelease(config, runner).run());

        assertFalse(runner.anyOutward(), reason + " must stop before any push or release: " + runner.commands);
        assertEquals(-1, runner.indexOf("git worktree add"), reason + " must stop before touching any branch");
        if (Set.of("gate-off", "missing-binary", "missing-payload").contains(reason)) {
            assertTrue(runner.commands.isEmpty(), reason + " runs nothing at all: " + runner.commands);
        }
    }

    /** A failed push leaves no release behind and still removes the worktree. */
    @Test
    void aFailedPushCreatesNoRelease_andStillRemovesTheWorktree() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.failOn = "git push";

        assertThrows(IOException.class, () -> new PublishBetaRelease(config(true), runner).run());

        assertEquals(-1, runner.indexOf("gh release create"), "no release without its pushed tag");
        assertTrue(runner.indexOf("git worktree remove --force") > runner.indexOf("git push"),
            "the worktree is removed even when the push fails");
    }

    /** The first beta release creates releases-beta as an orphan; later ones build on the fetched tip. */
    @Test
    void createsTheBranchAsAnOrphanOnlyWhenOriginLacksIt() throws Exception {
        FakeRunner first = new FakeRunner();
        new PublishBetaRelease(config(true), first).run();
        assertTrue(first.indexOf("git worktree add --orphan -b releases-beta") >= 0, first.commands.toString());

        FakeRunner later = new FakeRunner();
        later.remoteHeads = "abc\trefs/heads/releases-beta\n";
        new PublishBetaRelease(config(true), later).run();
        assertTrue(later.indexOf("git fetch origin releases-beta") < later.indexOf("git worktree add -B releases-beta"));
        assertEquals(-1, later.indexOf("git worktree add --orphan"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"draft", "not-prerelease", "missing-asset"})
    void anUnconfirmedReleaseFailsTheRun(String defect) throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.releaseView = switch (defect) {
            case "draft" -> runner.releaseView.replace("\"isDraft\":false", "\"isDraft\":true");
            case "not-prerelease" -> runner.releaseView.replace("\"isPrerelease\":true", "\"isPrerelease\":false");
            default -> runner.releaseView.replace(",{\"name\":\"shipsmooth-beta-0.1.0-beta-darwin-arm64.zip\"}", "");
        };

        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> new PublishBetaRelease(config(true), runner).run());
        assertTrue(e.getMessage().contains("not a complete, published pre-release"), e.getMessage());
    }

    @Test
    void parseArgs_isGatedOffUnlessTheFlagIsPassed() {
        String[] base = {"0.1.0-beta", "/repo", "/bin", "/payload", "/out"};
        PublishBetaRelease.Config off = PublishBetaRelease.parseArgs(base);
        assertFalse(off.enabled());
        assertEquals("v0.1.0-beta", off.tag());
        assertEquals(Path.of("/out/releases-beta-worktree"), off.worktreeDir());

        String[] gated = java.util.Arrays.copyOf(base, 6);
        gated[5] = "--publish-beta";
        assertTrue(PublishBetaRelease.parseArgs(gated).enabled());
        assertThrows(IllegalArgumentException.class, () -> PublishBetaRelease.parseArgs(new String[]{"0.1.0-beta"}));
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }
}
