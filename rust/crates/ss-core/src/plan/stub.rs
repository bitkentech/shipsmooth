//! Port of `Stub`: the skeleton a thin-context fast-start writes into
//! `plan-N.md` — a title, a Context placeholder echoing the user's words, and
//! a notional Tasks section, clearly marked as a stub to flesh out before
//! `plan init`.

pub fn stub_markdown(_plan_id: u32, _desc: &str) -> String {
    todo!("plan-102 Task 4")
}

#[cfg(test)]
mod tests {
    use super::*;

    // The Java text block, verbatim — the expected string IS the spec.
    #[test]
    fn renders_the_exact_java_stub_text() {
        let expected = "\
# plan-7 — Desktop UI

> **Stub** — quickstarted from a thin-context kickoff. Flesh this out
> before running `plan init`. Replace the placeholders below.

## Context

Feature (in the user's words): Desktop UI

_Unknowns: TODO — fill in scope, constraints, and the backlog/feature link._

## Tasks

_TODO — notional placeholder. Add `### Task N: Name [Risk]` headings here._
";
        assert_eq!(stub_markdown(7, "Desktop UI"), expected);
    }
}
