package io.bitken.ss.dist;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PublishBetaReleaseTest {

    @TempDir
    Path tmp;

    /** Records every command; answers the queries a release asks with canned output. */
    static final class FakeRunner implements PublishBetaRelease.CommandRunner {
        final List<String> commands = new ArrayList<>();
        String binaryVersion = "0.1.0-beta";
        String localTags = "";
        String releaseView = "{\"isDraft\":false,\"isPrerelease\":true,\"assets\":["
            + "{\"name\":\"shipsmooth-beta-0.1.0-beta-linux-x64.zip\"},"
            + "{\"name\":\"shipsmooth-beta-0.1.0-beta-darwin-x64.zip\"},"
            + "{\"name\":\"shipsmooth-beta-0.1.0-beta-darwin-arm64.zip\"}]}";

        @Override
        public String run(List<String> cmd, Path dir) {
            String line = String.join(" ", cmd);
            commands.add(line);
            if (line.endsWith("--version")) return binaryVersion + "\n";
            if (line.startsWith("git tag -l")) return localTags;
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
    @ValueSource(strings = {"gate-off", "version-mismatch", "tag-exists"})
    void refusesBeforeAnythingOutward(String reason) throws Exception {
        FakeRunner runner = new FakeRunner();
        boolean enabled = !reason.equals("gate-off");
        if (reason.equals("version-mismatch")) runner.binaryVersion = "0.0.9-beta";
        if (reason.equals("tag-exists")) runner.localTags = "v0.1.0-beta\n";

        assertThrows(IllegalStateException.class, () -> new PublishBetaRelease(config(enabled), runner).run());

        assertFalse(runner.anyOutward(), reason + " must stop before any push or release: " + runner.commands);
        if (reason.equals("gate-off")) assertTrue(runner.commands.isEmpty(), "gate off runs nothing at all");
    }
}
