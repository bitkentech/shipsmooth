package io.bitken.shipsmooth.tasks.git;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Resolves plan-version git tags ({@code plan-{N}-v*}). Lives in {@code git/}
 * because it shells out to {@code git} — the most stable code (XML marshalling)
 * shouldn't depend on the git runtime.
 */
public class GitTagService {

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
}
