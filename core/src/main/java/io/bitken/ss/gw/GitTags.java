package io.bitken.ss.gw;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Resolves and creates plan-version git tags ({@code plan-{N}-v*}).
 */
public class GitTags {

    /**
     * Returns the highest-numbered {@code plan-{N}-v*} tag in the repo,
     * or {@code plan-{N}-v1} when no tag is present or git is unavailable.
     */
    public String getPlanVersion(int planNum) {
        try {
            Process p = new ProcessBuilder("git", "tag", "-l",
                    versionPrefix(planNum) + "*", "--sort=-version:refname").start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                p.waitFor();
                return (line != null && !line.isBlank()) ? line.trim() : defaultVersion(planNum);
            }
        } catch (IOException | InterruptedException e) {
            return defaultVersion(planNum);
        }
    }

    /**
     * Computes the next version tag (e.g. if plan-7-v2 exists, returns plan-7-v3).
     * Returns plan-{N}-v1 if no version tags exist yet.
     */
    public String nextPlanVersion(int planNum) {
        String current = getPlanVersion(planNum);
        int k = parseVersion(current);
        return versionPrefix(planNum) + (k + 1);
    }

    /** True when the tag exists locally. */
    public boolean tagExists(String tag) {
        try {
            Process p = new ProcessBuilder("git", "tag", "-l", tag).start();
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
            return new ProcessBuilder("git", "tag", tag).start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
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
