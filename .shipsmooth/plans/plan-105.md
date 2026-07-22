# plan-105 — Fix unexpandable `~` in the POSIX CLI path (`XDG_CACHE_HOME` fallback)

## Context

Every POSIX host's rendered `SKILL.md` defines the CLI path as:

```bash
SS="${XDG_CACHE_HOME:-~/.cache}/shipsmooth/0.3.35/bin/shipsmooth"
```

The `~` sits inside a `${VAR:-default}` expansion **inside double quotes**, where
bash performs no tilde expansion. When `XDG_CACHE_HOME` is unset the default
branch fires and `$SS` becomes the literal string `~/.cache/shipsmooth/.../bin/shipsmooth`,
which resolves to nothing.

Source: `plugin-model/src/main/java/io/bitken/ss/resources/Os.java:45`.

### Why this reads as a macOS bug

It is not platform-specific code — it is a platform-specific *trigger*:

- **Linux** desktop sessions commonly export `XDG_CACHE_HOME`, so the broken
  default branch never fires.
- **macOS** has no XDG convention, so the variable is effectively always unset
  and the broken branch fires on every invocation.

### Why it went unnoticed until 0.3.35

Tilde expansion happens at *parse* time, before parameter expansion, so a `~`
arriving via a variable's value is never expanded — regardless of quoting at the
use site. Verified in this repo's shell with a real executable staged at the
target path:

```
SS = ~/.cache/ss-repro-test/bin/fake
A: unquoted $SS   → No such file or directory
B: quoted "$SS"   → No such file or directory
C: [ -x "$SS" ]   → FAILED
```

At the *agent* layer the failure is intermittent, which is what made field
reports irreproducible: the defect is visible to a competent reader, so a model
consuming the skill may silently substitute `$HOME` and succeed. Observed
directly — a Claude Code session in this repo reported success while stating
"I substituted `$HOME` instead, which is why it worked." A skill line whose
correctness depends on the reading model choosing to repair it is broken
regardless of its current success rate.

### Regression provenance

This is a regression, introduced 2026-05-19 in commit `9c107fb`
(`task(4): cliBin uses XDG shell expression`) under plan-45.

Before that commit, `ResourceBuilder.java:24-28` derived `cliBin` from a
Maven-resolved property — `<shipsmooth.cache.dir.resolved>${user.home}/.cache/shipsmooth`
— so the skill shipped a fully-resolved absolute path. No runtime shell
expansion was involved and the tilde failure mode did not exist.

Commit `9c107fb` replaced that with the deferred shell expression
`"${XDG_CACHE_HOME:-~/.cache}/shipsmooth/runtime-" + pluginVersion + ...`.
The line then survived verbatim through the plan-79 module split (`88889fc`),
which moved it into `Os.java` where it lives today. `git log --follow` on
`Os.java` shows only the split commit; the real lineage runs back through
`ResourceBuilder.java`.

**The change itself was correct.** The old baked path hardcoded the *build
machine's* home directory into a shipped artifact and ignored `XDG_CACHE_HOME`
entirely — broken for every user who was not the release author. Plan-45's goal
was right; the defect is one token in the fallback, `~` where `$HOME` was
needed.

Why it went unreviewed for ~14 months, and what it implies for Task 1:

1. Plan-45 rated Task 4 **[Low]** risk.
2. Plan-45 line 108 **specified the defective literal verbatim**, so the
   implementation faithfully executed an already-wrong plan and no
   plan-vs-code divergence existed for review to catch.
3. The same commit **rewrote the test assertion to pin the new literal**,
   converting the guard into a ratifier of the bug.

Point 3 is the precedent behind Task 1's High rating: the last person to touch
this line updated the assertions to match the code, and that locked the defect
in. Note also that plan-45 line 103 records that the Node resolver "expands
tilde for dev" — the tilde was understood on the resolver side, and only its
shell-quoting consequence failed to carry across to the emitter.

The cross-reference comment now at `session-start.ts:164` was added by this
same commit as the intended safeguard against exactly this drift. It was
one-directional (Node side only, nothing in `Os.java` points back) and its
target path — `plugin-skill/src/main/jte-src/skills/_partials/base-workflow.jte.md`
— no longer exists after the plan-79 split. Task 1 updates it.

### Findings from the repo-wide sweep

A sweep for `${VAR:-~...}` and `"..~/..` across Java/Kotlin/TS/JS/sh sources and
all rendered host outputs found:

- **One production emitter:** `Os.java:45`. `Os.Windows.cliBinPath` (line 62) is
  unaffected; `TargetIntegrationTest:268` already asserts Windows never
  references `XDG_CACHE_HOME`.
- **Six test assertions** pin the broken literal and must move with the fix:
  `plugin-model/.../OsTest.java:36,42` and
  `harness/shared/.../TargetIntegrationTest.java:51,223,235,343`.
- **The write path is already correct** — `install-shipsmooth.sh:19` uses
  `${XDG_CACHE_HOME:-$HOME/.cache}` and `session-start.ts:171` uses
  `os.homedir()`. This asymmetry is the whole bug: the runtime installs to the
  right place, and only the skill's read-back path cannot find it.
- **No other live instances.** Remaining `~/` hits are historical plan prose
  (`plan-45/61/69/71/77/86/96.md`), benign narrative in `SKILL.md:48` and
  gemini `README.md:21`, an OpenCode source comment, a `smoke-gemini.sh` echo
  string, and illustrative Java literals in the `single-source` refine rule.
  None are executed as paths.

The fix is `~` → `$HOME`, matching the installer that already gets it right.

## Scope

In scope: the one-line fix, the six pinning assertions, a regression guard that
fails if any rendered POSIX `SKILL.md` reintroduces an unexpandable tilde, and
the documented sweep above.

Out of scope: the release decision (made at closeout), and the benign prose
hits, which are left as-is.

---

### Task 1: Fix the POSIX `cliBinPath` fallback and repoint pinning assertions [High]

Change `Os.java:45` to emit `${XDG_CACHE_HOME:-$HOME/.cache}`, and update the
six assertions that pin the old literal (`OsTest.java:36,42`;
`TargetIntegrationTest.java:51,223,235,343`) to expect `$HOME/.cache`.

Also repoint the stale cross-reference comment at `session-start.ts:164` to
`Os.java`, and add the reciprocal pointer in `Os.java` so the pairing is
two-directional.

Risk is High not for diff size but because these tests are the only thing
pinning the emitted contract: updating an assertion to match whatever the code
now produces is exactly how a wrong value gets locked in — see Regression
provenance, where that is precisely how this defect was locked in for ~14
months. Assert the intended string literally, derived from the bug analysis,
not from captured output. Leave `Os.Windows.cliBinPath` untouched.

### Task 2: Regression guard against an unexpandable tilde in rendered skills [Medium]

*Depends-on: 1*

Add a test asserting no rendered POSIX `SKILL.md` contains `${` … `:-~`. It must
fail against the pre-fix rendering and pass after, so verify it red first by
reverting Task 1 locally.

Prefer extending `TargetIntegrationTest` (it already renders per-host targets
and owns the Windows-exclusion assertion) over a new harness. Guard the emitted
*pattern*, not one hard-coded path string, so it survives version bumps — the
existing assertions already pin exact paths and would not catch a tilde
reintroduced elsewhere in the line.

### Task 3: Verify the fix end-to-end with `XDG_CACHE_HOME` unset [Low]

*Depends-on: 1, 2*

Render the skill for a POSIX host, confirm line 54 reads `$HOME/.cache`, then
with `env -u XDG_CACHE_HOME` confirm the emitted `SS=` assignment resolves to a
real path and the CLI executes — the exact scenario that fails on 0.3.35.

This is the macOS condition reproduced on Linux by forcing the env state macOS
has natively, and it closes the loop the shell-level repro opened.
