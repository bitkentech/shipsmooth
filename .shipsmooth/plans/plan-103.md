# Plan 103 — `plan init`: fail loudly and diagnose malformed task headings

## Context

**Field report (2026-07-19, fresh macOS install, 0.3.34, Claude Code
Desktop):** an agent ran
`plan init --plan 999 --tasks-from /tmp/test_plan_v4.md` and got
`Written 0 tasks to .../plan-999-tasks.xml` with exit 0. It retried "various
Markdown formats", still got 0 tasks, concluded the parser was broken in its
environment, and abandoned the shipsmooth workflow for the host's own task
tools. That is the worst failure mode: the tool silently discredits itself.

**Investigation — this is not a macOS bug.** Reproduced on Linux with the
exact regex from `PlanMarkdownParser`:

- `## Task 1: Name [High]` (h2) → **0 tasks**; `### Task 1: Name [High]` (h3)
  → 2 tasks. CRLF line endings parse fine. Charset is UTF-8 on all platforms
  (Java 18+). Nothing platform-specific anywhere on the path.
- Root cause is a UX-contract gap, in three parts:
  1. `PlanMarkdownParser.HEADING` accepts exactly one grammar —
     `### Task N: Name [High|Medium|Low]` — and silently drops everything
     else (wrong heading level, missing colon, `Task One`, bold-list styles).
  2. `cli/.../plan/Init.java` writes the XML and exits 0 even when 0 tasks
     parsed. No error, no hint, plus a junk zero-task XML file left behind.
  3. The start skill (`skills/shared/workflow/phase1-plan.jte.md:36`)
     deliberately does not document the grammar; it promises instead that the
     CLI "validates their form — if a heading or dependency line is malformed
     it will tell you". **No such validation exists.** The promise is the bug:
     progressive disclosure relies on CLI feedback that was never built.
- Same silent-drop applies to `*Depends-on: 1,2*` lines: a near-miss (e.g.
  missing asterisks, `Depends-on: Task 1`) is dropped without a word,
  producing a plan whose dependency ordering is silently wrong.

**Fix direction:** keep the single canonical grammar (one format to rule them
all — widening invites divergent plan files across repos), but make the CLI
honor the skill's promise: zero parsed tasks becomes a hard error that states
the expected grammar and points at the near-miss lines it can see; a
successful parse still reports near-miss heading/depends-on lines so a
mostly-right file can't silently lose a task.

Backlog feature: agent-facing robustness of the plan-authoring workflow
(the CLI must be self-explanatory when its input contract is violated, since
skill prose deliberately omits the grammar).

## Desired behavior

- `plan init` parsing **0 tasks**: exit 1, write **no** XML file, and print to
  stderr the expected grammar (`### Task N: <name> [High|Medium|Low]`,
  `*Depends-on: 1,2*`) plus up to ~10 near-miss lines with line numbers and a
  reason each (wrong heading level / missing colon / non-numeric id / bad risk
  tag).
- `plan init` parsing **≥1 task** but with near-miss heading or depends-on
  lines: succeed, but print the near-miss report to stdout (success-path info
  stays on stdout per project convention) so a dropped task/dependency is
  visible immediately.
- Diagnostics must not fire on ordinary prose that mentions "task" — only on
  lines that structurally look like an attempted task heading or depends-on
  marker.

## Non-goals

- Not widening the accepted heading grammar (no h2/h4 acceptance) — canonical
  form stays; the fix is feedback, not leniency. (Open question below.)
- Not touching the 500-char depends-on search cap (PB-352) or the
  `plan init` re-run state-reset footgun — both tracked separately.
- Not porting any of this to the plan-102 Rust spike; parity noted as a
  follow-up for whenever the Rust parser port resumes.

## Open questions

- ~~Should the grammar itself also be widened (accept `##`–`####` heading
  levels)?~~ **Resolved at calibration: diagnose-only.** One canonical
  format; a good error message makes leniency unnecessary.

## Tasks

### Task 1: Parser near-miss diagnostics in PlanMarkdownParser [High]

Core logic. Extend `PlanMarkdownParser` to return, alongside the parsed
tasks, a list of diagnostics: line number, offending line, and reason.
Candidate heuristic (design core of this task): a line is a near-miss when it
structurally resembles a task heading — starts with `#{1,6}`, a bold marker,
or a list marker followed by `Task <something>`, or is exactly a
`### Task ...` line that fails the strict pattern — but does not match the
canonical grammar. Classify the reason (wrong heading level, missing colon,
non-numeric id, invalid risk tag). Must produce zero diagnostics on all
existing valid plan files in `.shipsmooth/plans/` (regression sweep) and on
prose paragraphs mentioning "task".

### Task 2: `plan init` fails on zero tasks with grammar + diagnostics [Low]

*Depends-on: 1*

Wire the parse report into `cli/.../plan/Init.java`: zero tasks → exit 1,
no XML written, stderr message stating the canonical grammar and listing the
near-miss diagnostics. Non-zero tasks with diagnostics → still exit 0, print
the near-miss report to stdout. Existing tests (`CommandsTest`) only cover
valid input and a missing file, so no collisions expected.

### Task 3: Depends-on near-miss diagnostics [Low]

*Depends-on: 1*

Same treatment for dependency lines: within a task's region, a line matching
a relaxed `depends-on` pattern that fails the strict
`*Depends-on: 1,2*` grammar becomes a diagnostic (reported via the same
channel as Task 2's output). Guards against silently dropped dependency
ordering.

### Task 4: Align skill prose with the now-true promise [Low]

*Depends-on: 2*

Update `skills/shared/workflow/phase1-plan.jte.md`: keep the "it will tell
you" sentence (now true) and add the one-line canonical grammar as an inline
hint so an agent authoring the plan file can get it right on the first try.
Verify rendered SKILL.md output for all four hosts picks it up.

### Task 5: Reproduce the field failure in Claude Code Desktop for Linux [Low]

Independent of the code tasks; can run before or alongside them. The field
failure occurred in Claude Code Desktop on macOS. Repeat the scenario in
Claude Code Desktop for **Linux** with shipsmooth 0.3.34: have the agent
author a wrong-format plan (h2 `## Task N:` headings, as in the report) and
run `plan init` on it. Expected: the same `Written 0 tasks` with exit 0 —
confirming in the real harness that the failure is platform-independent and
matches the parser-level reproduction above. After Task 2 lands, re-run once
against the fixed build to confirm the new error output steers the agent to
the canonical grammar instead of driving it to abandon the workflow.
(User-driven verification; agent assists.)
