package io.bitken.ss.svc.plan;

import io.bitken.ss.conf.ShipsmoothDataLocator;
import io.bitken.ss.gw.GitState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A plan that does not yet exist, knowing how to bring itself into being.
 *
 * <p>{@link #scaffold} derives the next plan id, creates and checks out its
 * task branch, and writes a stub plan file — and deliberately does <em>not</em>
 * commit. Keeping plan-file authoring (and the absence of any git-write
 * collaborator) inside this object is what removes the "commit the file I just
 * wrote" lure from a calling agent: there is nothing here that can commit.
 */
public final class NewPlan {

    private final PlanNumbers planNumbers;
    private final GitState gitState;
    private final ShipsmoothDataLocator locator;

    public NewPlan(PlanNumbers planNumbers, GitState gitState, ShipsmoothDataLocator locator) {
        this.planNumbers = planNumbers;
        this.gitState = gitState;
        this.locator = locator;
    }

    /**
     * Scaffolds a new plan from {@code desc}. Checks branch availability before
     * touching the filesystem, so a collision leaves no stray stub behind.
     */
    public ScaffoldResult scaffold(String desc) throws ScaffoldException, IOException {
        int planId = planNumbers.next();
        String branchName = Slugs.branchName(String.valueOf(planId), desc);

        createBranch(branchName, planId);
        Path planFile = writeStub(planId, desc);

        return new ScaffoldResult(planId, branchName, planFile);
    }

    private void createBranch(String branchName, int planId) throws ScaffoldException {
        if (gitState.branchExists(branchName)) {
            throw new ScaffoldException("branch " + branchName
                + " already exists — did you mean to resume plan " + planId + "?");
        }
        if (!gitState.createBranch(branchName)) {
            throw new ScaffoldException("failed to create branch " + branchName);
        }
    }

    private Path writeStub(int planId, String desc) throws IOException {
        Path planFile = locator.planMarkdownFile(planId).toPath();
        Files.createDirectories(planFile.getParent());
        Files.writeString(planFile, Stub.markdown(planId, desc));
        return planFile;
    }
}
