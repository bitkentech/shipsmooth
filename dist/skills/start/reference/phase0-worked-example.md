# Phase 0 Thin-path — Worked Example (reference)

Kickoff: *"start a new plan, feature is X"* — no spec, no prior planning.

- ✅ **Target:** run `$SS plan quick --desc "X"` → relay its
  output (branch + stub created, uncommitted) → **stop**.
- ❌ **Anti-target #1:** run several rounds of repo investigation, then fire a
  multi-part questionnaire asking the user to choose the approach, before
  creating anything. This interrogates the user at the moment he wanted to move
  fast. *Do not do this.*
- ❌ **Anti-target #2:** after `plan quick` (or instead of it), hand-write the
  stub file and `git commit` it. The commit is unrequested git work that can
  fail on an unconfigured identity and strand the flow. *Do not do this.*
