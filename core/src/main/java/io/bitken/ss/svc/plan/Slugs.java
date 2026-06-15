package io.bitken.ss.svc.plan;

import java.text.Normalizer;

/**
 * Turns a free-text description into a branch-safe slug and the
 * {@code t/{prefix}-{slug}} task-branch name.
 *
 * <p>Folds accented Latin to ASCII (NFD + strip combining marks) before the
 * lowercase / non-alphanumeric-to-hyphen / trim transform, so {@code "Café
 * déjà"} slugs to {@code cafe-deja} rather than the lossy {@code caf-d-j}. No
 * external slug library — these are short dev-authored phrases.
 */
public final class Slugs {

    private Slugs() {
    }

    public static String slugify(String text) {
        String folded = Normalizer.normalize(text, Normalizer.Form.NFD)
                                  .replaceAll("\\p{M}+", "");
        return folded.toLowerCase()
                     .replaceAll("[^a-z0-9]+", "-")
                     .replaceAll("^-|-$", "");
    }

    /**
     * The {@code t/{prefix}-{slug}} task-branch name, omitting the trailing
     * hyphen when the description slugs to nothing.
     */
    public static String branchName(String prefix, String desc) {
        String slug = slugify(desc);
        return slug.isEmpty() ? "t/" + prefix : "t/" + prefix + "-" + slug;
    }
}
