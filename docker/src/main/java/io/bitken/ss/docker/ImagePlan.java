package io.bitken.ss.docker;

import io.bitken.ss.docker.ResolveVersions.Versions;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure description of what to build and where to push it — no process execution.
 * Kept separate from {@link BuildAndPushImage} so the command construction (the
 * part that is easy to get subtly wrong) is unit-testable without Docker.
 */
public final class ImagePlan {

    private final List<String> tags;
    private final Versions versions;
    private final String contextDir;
    private final String dockerfile;
    private final String vcsRef;
    private final String buildDate;

    private ImagePlan(
            List<String> tags,
            Versions versions,
            String contextDir,
            String dockerfile,
            String vcsRef,
            String buildDate) {
        this.tags = List.copyOf(tags);
        this.versions = versions;
        this.contextDir = contextDir;
        this.dockerfile = dockerfile;
        this.vcsRef = vcsRef;
        this.buildDate = buildDate;
    }

    /** {@code latest}, the dated tag, and the compound {@code claude-<v>-ss-<v>} tag. */
    public static List<String> standardTags(String repo, Versions versions, String date) {
        return List.of(
                repo + ":latest",
                repo + ":" + date,
                repo + ":" + versions.compoundTag());
    }

    /** A build tagged with the standard three-tag set. {@code buildDate} is RFC 3339. */
    public static ImagePlan forStandardTags(
            String repo,
            Versions versions,
            String contextDir,
            String dockerfile,
            String vcsRef,
            String buildDate) {
        return new ImagePlan(
                standardTags(repo, versions, buildDate.substring(0, 10)),
                versions,
                contextDir,
                dockerfile,
                vcsRef,
                buildDate);
    }

    /** A build tagged with a single explicit tag (the smoke test uses this). */
    public static ImagePlan forExplicitTag(
            String tag,
            Versions versions,
            String contextDir,
            String dockerfile,
            String vcsRef,
            String buildDate) {
        return new ImagePlan(List.of(tag), versions, contextDir, dockerfile, vcsRef, buildDate);
    }

    public List<String> tags() {
        return tags;
    }

    /** The full {@code docker build ...} argv, context dir last. */
    public List<String> buildArgv() {
        List<String> argv = new ArrayList<>(List.of("docker", "build"));
        // Build-time API key: stays out of image layers (build runs on the laptop,
        // where the key already lives). env= form matches the documented build.
        argv.add("--secret");
        argv.add("id=claude_api_key,env=CLAUDE_API_KEY");
        addBuildArg(argv, "CLAUDE_VERSION", versions.claudeCode());
        addBuildArg(argv, "SHIPSMOOTH_VERSION", versions.shipsmooth());
        addBuildArg(argv, "IMAGE_VERSION", versions.compoundTag());
        addBuildArg(argv, "VCS_REF", vcsRef);
        addBuildArg(argv, "BUILD_DATE", buildDate);
        for (String tag : tags) {
            argv.add("-t");
            argv.add(tag);
        }
        argv.add("-f");
        argv.add(dockerfile);
        argv.add(contextDir);
        return List.copyOf(argv);
    }

    /** One {@code docker push <tag>} argv per tag, in tag order. */
    public List<List<String>> pushArgvs() {
        return tags.stream().<List<String>>map(tag -> List.of("docker", "push", tag)).toList();
    }

    private static void addBuildArg(List<String> argv, String key, String value) {
        argv.add("--build-arg");
        argv.add(key + "=" + value);
    }
}
