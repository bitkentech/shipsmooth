package io.bitken.ss.dist;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * plan-115: the publish flow against REAL git — a local bare repo stands in for origin —
 * with only {@code gh} stubbed. Two consecutive releases exercise both branch paths: the
 * first creates {@code releases-beta} as an orphan, the second builds on it. The caller's
 * checkout must never be switched or dirtied.
 */
class PublishBetaReleaseIntegrationTest {

    @TempDir
    Path tmp;

    private final List<String> ghCalls = new ArrayList<>();

    private String git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        return PublishRelease.runCommand(cmd, dir);
    }

    /** Real commands, except gh: recorded, and `gh release view` answers as GitHub would. */
    private String run(List<String> cmd, Path dir, String version) throws IOException, InterruptedException {
        if (cmd.get(0).equals("gh")) {
            ghCalls.add(String.join(" ", cmd));
            if (cmd.get(2).equals("view")) {
                StringBuilder assets = new StringBuilder();
                for (String p : PackageBeta.PLATFORMS) {
                    if (assets.length() > 0) assets.append(',');
                    assets.append("{\"name\":\"").append(PackageBeta.assetName(version, p)).append("\"}");
                }
                return "{\"isDraft\":false,\"isPrerelease\":true,\"assets\":[" + assets + "]}";
            }
            return "";
        }
        return PublishRelease.runCommand(cmd, dir);
    }

    private PublishBetaRelease.Config release(Path repo, String version) throws Exception {
        Path binDir = tmp.resolve("bin-" + version);
        for (String platform : PackageBeta.PLATFORMS) {
            Path bin = Files.createDirectories(binDir.resolve(platform)).resolve("shipsmooth-beta");
            Files.writeString(bin, "#!/bin/sh\necho " + version + "\n");
            Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        Path payload = Files.createDirectories(tmp.resolve("payload-" + version + "/hooks"));
        Files.writeString(payload.resolve("hooks.json"), "{\"v\":\"" + version + "\"}");
        Path script = payload.resolve("install-shipsmooth-beta.sh");
        Files.writeString(script, "#!/bin/sh\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return new PublishBetaRelease.Config(true, version, repo, binDir, tmp.resolve("payload-" + version),
            tmp.resolve("dist-" + version), tmp.resolve("worktree-" + version));
    }

    @Test
    void twoReleases_buildTheReleasesBetaBranch_andLeaveTheCheckoutAlone() throws Exception {
        Path origin = Files.createDirectories(tmp.resolve("origin.git"));
        git(origin, "init", "-q", "--bare", "-b", "main");
        Path repo = Files.createDirectories(tmp.resolve("repo"));
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.email", "test@example.com");
        git(repo, "config", "user.name", "Test");
        Files.writeString(repo.resolve("README.md"), "main\n");
        git(repo, "add", "README.md");
        git(repo, "commit", "-q", "-m", "main");
        git(repo, "remote", "add", "origin", origin.toString());
        git(repo, "push", "-q", "origin", "main");
        String head = git(repo, "rev-parse", "HEAD").strip();

        for (String version : List.of("0.1.0-beta", "0.1.0-beta.2")) {
            new PublishBetaRelease(release(repo, version), (cmd, dir) -> run(cmd, dir, version)).run();
        }

        // The branch carries one commit per release, each with only the payload at dist/.
        assertEquals("release: v0.1.0-beta.2 (main: " + head.substring(0, 7) + ")\n"
                + "release: v0.1.0-beta (main: " + head.substring(0, 7) + ")",
            git(origin, "log", "--format=%s", "releases-beta").strip());
        assertEquals("dist/hooks/hooks.json\ndist/hooks/install-shipsmooth-beta.sh",
            git(origin, "ls-tree", "-r", "--name-only", "releases-beta").strip(),
            "orphan branch: no main-branch files, only dist/");
        assertEquals("{\"v\":\"0.1.0-beta.2\"}", git(origin, "show", "releases-beta:dist/hooks/hooks.json"));
        assertTrue(git(origin, "ls-tree", "releases-beta", "dist/hooks/install-shipsmooth-beta.sh").startsWith("100755"),
            "the installer keeps its executable bit");
        // Each tag points at its own release commit on releases-beta.
        assertEquals(git(origin, "rev-parse", "releases-beta").strip(), git(origin, "rev-parse", "v0.1.0-beta.2^{commit}").strip());
        assertEquals(git(origin, "rev-parse", "releases-beta~1").strip(), git(origin, "rev-parse", "v0.1.0-beta^{commit}").strip());

        // The caller's checkout is untouched: same branch, same HEAD, clean, no leftover worktrees.
        assertEquals("main", git(repo, "rev-parse", "--abbrev-ref", "HEAD").strip());
        assertEquals(head, git(repo, "rev-parse", "HEAD").strip());
        assertEquals("", git(repo, "status", "--porcelain").strip());
        assertEquals(1, git(repo, "worktree", "list").strip().lines().count());
        assertEquals(4, ghCalls.size(), "create + view per release: " + ghCalls);
    }
}
