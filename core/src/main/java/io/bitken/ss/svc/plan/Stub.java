package io.bitken.ss.svc.plan;

/**
 * The skeleton a thin-context fast-start writes into {@code plan-N.md}: a title,
 * a Context placeholder echoing the user's words, and a notional Tasks section —
 * clearly marked as a stub to flesh out before {@code plan init}.
 */
final class Stub {

    private Stub() {
    }

    static String markdown(int planId, String desc) {
        return """
            # plan-%d — %s

            > **Stub** — fast-started from a thin-context kickoff. Flesh this out
            > before running `plan init`. Replace the placeholders below.

            ## Context

            Feature (in the user's words): %s

            _Unknowns: TODO — fill in scope, constraints, and the backlog/feature link._

            ## Tasks

            _TODO — notional placeholder. Add `### Task N: Name [Risk]` headings here._
            """.formatted(planId, desc, desc);
    }
}
