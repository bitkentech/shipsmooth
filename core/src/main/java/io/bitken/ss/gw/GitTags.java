package io.bitken.ss.gw;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;

/**
 * Resolves and creates plan-version git tags ({@code plan-{N}-v*}). All git
 * commands run in the configured {@code workDir} (the repo root); running them
 * in the JVM's inherited CWD breaks tagging whenever the CLI is invoked from
 * anywhere but the repo root.
 */
public class GitTags {

    private final Path workDir;

    public GitTags(Path workDir) {
        this.workDir = workDir;
    }

    /**
     * Returns the highest-numbered {@code plan-{N}-v*} tag in the repo,
     * or {@code plan-{N}-v1} when no tag is present or git is unavailable.
     */
    public String getPlanVersion(int planNum) {
        String highest = highestVersionTag(planNum);
        return highest != null ? highest : defaultVersion(planNum);
    }

    /**
     * Computes the next version tag. Returns {@code plan-{N}-v1} when no version
     * tags exist yet, and {@code plan-{N}-v{K+1}} when the highest is {@code vK}.
     */
    public String nextPlanVersion(int planNum) {
        String highest = highestVersionTag(planNum);
        int next = highest == null ? 1 : parseVersion(highest) + 1;
        return versionPrefix(planNum) + next;
    }

    /**
     * Returns the highest-numbered {@code plan-{N}-v*} tag, or {@code null} when
     * none exists or git is unavailable. Distinguishing "no tag" from "v1" is
     * what lets {@link #nextPlanVersion} start at v1 instead of v2.
     */
    private String highestVersionTag(int planNum) {
        try {
            Process p = pb("git", "tag", "-l",
                    versionPrefix(planNum) + "*", "--sort=-version:refname").start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                p.waitFor();
                return (line != null && !line.isBlank()) ? line.trim() : null;
            }
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    /** True when the tag exists locally. */
    public boolean tagExists(String tag) {
        try {
            Process p = pb("git", "tag", "-l", tag).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                return line != null && !line.isBlank();
            }
        } catch (IOException e) {
            return false;
        }
    }

    /** Creates a local tag at HEAD. Returns true on success. */
    public boolean createTag(String tag) {
        try {
            return pb("git", "tag", tag).start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private ProcessBuilder pb(String... cmd) {
        return new ProcessBuilder(cmd).directory(workDir.toFile());
    }

    private static String versionPrefix(int planNum) {
        return "plan-" + planNum + "-v";
    }

    private static String defaultVersion(int planNum) {
        return versionPrefix(planNum) + "1";
    }

    private static int parseVersion(String tag) {
        int dashV = tag.lastIndexOf("-v");
        if (dashV < 0) return 0;
        try {
            return Integer.parseInt(tag.substring(dashV + 2));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
