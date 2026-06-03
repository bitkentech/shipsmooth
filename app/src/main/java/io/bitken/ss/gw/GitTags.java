package io.bitken.ss.gw;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Resolves and creates plan-version git tags ({@code plan-{N}-v*}).
 */
public class GitTags {

    /**
     * Returns the highest-numbered {@code plan-{planNum}-v*} tag in the repo,
     * or {@code "plan-{planNum}-v1"} when no tag is present or git is unavailable.
     */
    public String getPlanVersion(int planNum) {
        String planVersion = "plan-" + planNum + "-v1";
        try {
            Process process = new ProcessBuilder("git", "tag", "-l",
                    "plan-" + planNum + "-v*", "--sort=-version:refname").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    planVersion = line.trim();
                }
            }
        } catch (IOException e) {
            // Ignore and use default
        }
        return planVersion;
    }

    /**
     * Computes the next version tag for a plan (e.g. if plan-7-v2 exists, returns plan-7-v3).
     * Returns plan-{N}-v1 if no version tags exist yet.
     */
    public String nextPlanVersion(int planNum) {
        String current = getPlanVersion(planNum);
        // current is plan-N-vK; parse K and increment
        int dashV = current.lastIndexOf("-v");
        if (dashV < 0) return "plan-" + planNum + "-v1";
        try {
            int k = Integer.parseInt(current.substring(dashV + 2));
            return "plan-" + planNum + "-v" + (k + 1);
        } catch (NumberFormatException e) {
            return "plan-" + planNum + "-v1";
        }
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
            Process p = new ProcessBuilder("git", "tag", tag).start();
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
