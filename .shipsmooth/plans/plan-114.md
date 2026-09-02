# plan-114 — Bake a Claude Code status line into the `shipsmooth-claude` image

**Backlog feature:** operate the `bitkentech/shipsmooth-claude` sandbox image as a
first-class distribution channel (local backlog; no external issue). Continues
plan-112 (`DOCKER.md` + the README "Docker (Claude Code)" method) and plan-113
(folding the image build into the `docker/` module). This plan makes the
pull-and-run image show the same status line a tuned host setup shows.

## Context

Claude Code renders a status line when `settings.json` carries a `statusLine`
key of the form `{"type":"command","command":"<path>"}`; on each render it pipes
a JSON blob (model, context window, rate limits) to that command on stdin and
prints the command's stdout. In the container `$HOME` is `/root`, so the image
needs two things baked in:

1. the script at a stable path — chosen: `/root/.claude/scripts/statusline.sh`
   (mirrors the `~/.claude/scripts/` layout of a tuned host), executable;
2. `/root/.claude/settings.json` gaining
   `"statusLine": {"type":"command","command":"~/.claude/scripts/statusline.sh"}`.

### What makes this non-trivial

1. **The settings write must merge, not overwrite.** The plugin-install step
   (`docker/Dockerfile:46-48`, `claude plugin install shipsmooth --scope user`)
   already writes `/root/.claude/settings.json`. In the published image it holds:

   ```json
   { "extraKnownMarketplaces": { ... }, "enabledPlugins": { "shipsmooth@bitkentech": true } }
   ```

   A `COPY settings.json` would clobber that, and `docker/smoke.sh:72-75` greps
   for exactly `installed_plugins.json` + the `"shipsmooth@bitkentech": true`
   line — so the smoke test would catch a clobber, but only after a full image
   build on a Docker host. Hence an in-place `jq` merge, in a layer that sits
   **after** the plugin install (`docker/Dockerfile:48`).

2. **No new packages.** `statusline.sh` needs `jq` (`docker/Dockerfile:19`) and
   `bc` (`docker/Dockerfile:20`) — both already in the apt list. `date -d @<epoch>`
   is GNU coreutils, present on Ubuntu.

3. **Build context is `docker/`.** `docker/build.gradle.kts` pins the build
   task's `workingDir` to the module dir, and `BuildAndPushImage` derives both
   the Dockerfile and the build context from `user.dir`
   (`BuildAndPushImage.java:231-236` → `ImagePlan`). `docker/.dockerignore`
   currently sends **only** `Dockerfile` to the daemon (`*` then `!Dockerfile`).
   So the script source must live under `docker/` and `.dockerignore` must
   re-include it.

4. **The volume-seeding caveat is a doc addition, not a build problem.** Docker
   seeds a named volume from the image *only when the volume is empty*. Anyone
   with a pre-existing `cc-config` volume (`-v cc-config:/root/.claude`) gets no
   status line until `docker volume rm cc-config` + re-auth — the same caveat
   `DOCKER.md:99-106` already documents for the bundled plugin version. Extend
   that note; nothing to solve in the build.

### Source script

The status line script is ported from `/workspace/cc-setup/statusline.sh`
(verified 2026-09-02). That file has **no host addresses, SSH endpoints, or
hostnames** — the private material plan-113 kept in `cc-setup` lives in the
*other* files (`server-notes*`, `connect-*.sh`, `login-options.md`), not this
one — so "scrub" here is a re-verification, not a rewrite. It prints one line:

```
<model> | ctx: <pct>% (<tokens>) | pro: <pct>% (till <time>) | wk: <time-rem>% / <usage-rem>% (time rem / usage rem)
```

reading `.model.display_name`, `.context_window.*`, and `.rate_limits.{five_hour,seven_day}.*`
from the stdin JSON, with `bc` for the token/percentage formatting and colour
escapes when context is ≥60% / ≥80%.

**Ported byte-for-byte** (calibration decision 2026-09-02). The source emits
`pro: 12.50%%` — a literal double `%` (`pro_display` is already
`printf "%.2f%%"` and the final `echo` appends another `%`). This is retained
as-is; the port introduces no behavioural changes. Any tidy-up is a separate,
later change against `docker/scripts/statusline.sh`.

### Design decisions

1. **Script source at `docker/scripts/statusline.sh`** — mirrors the in-image
   `~/.claude/scripts/` destination. `.dockerignore` gains `!scripts` (the dir
   holds only this one file; re-including the directory is simpler and more
   robust across Docker versions than a bare `!scripts/statusline.sh` whose
   parent stays excluded).
2. **In-image path `/root/.claude/scripts/statusline.sh`**, `chmod +x`. The
   `settings.json` value is `~/.claude/scripts/statusline.sh` (Claude Code
   expands `~`), matching how a host config references it.
3. **`jq` merge, own RUN layer, immediately after the plugin-install layer**
   (`docker/Dockerfile:48`) and before the `LABEL` block — it is a config step,
   not an identity/label step. `jq '.statusLine = {...}' settings.json > tmp &&
   mv tmp settings.json`.
4. **Two regression guards, at two altitudes:**
   - a JUnit test in the `docker/` module that reads `Dockerfile` as text and
     asserts the `COPY scripts/statusline.sh` + `jq '.statusLine' ` lines exist
     **and appear after** the `claude plugin install` line — the "careless
     future edit reorders the layers" regression, caught in plain
     `./gradlew :docker:test` with no Docker;
   - `docker/smoke.sh` assertions (Docker host only): `enabledPlugins` still
     present after the merge, `.statusLine.command` set, and the script exists
     and is executable in the image.
   This matches the existing smoke-test spirit ("labels don't lie about image
   contents").
5. **A unit test for the script itself** — run `docker/scripts/statusline.sh`
   with a canned JSON fixture on stdin (the build container has `jq`/`bc`/`date`)
   and assert the output shape: contains the model name and the `ctx:` / `pro:` /
   `wk:` fields, exit 0. It asserts the ported contract, not a tidied one (the
   `pro: …%%` double `%` is expected — see "Source script").

### TDD invariant

The runtime surface is a shell script + Dockerfile lines + docs. "Tests precede
implementation" applies as far as possible (plan-112 precedent): the two JUnit
tests (script behaviour, Dockerfile layer ordering) are written red-first. The
end-to-end proof — a real `docker build` + `docker run` with the status line
active — is `docker/smoke.sh`, which needs a Docker host and is run by the user
off-container (as with plan-113).

## Open questions — resolved 2026-09-02 (Phase 1 calibration)

1. **Fix the `%%` bug during the port, or port byte-for-byte?** → **Port
   byte-for-byte.** `pro: …%%` is retained; any tidy-up is a later, separate
   change.
2. **Also surface the status line in `DOCKER.md` §5 ("Launch Claude")**, or only
   extend the §7 volume caveat? → **Both.** One sentence in §5 (the image ships
   a status line showing model / context / rate-limit usage) *and* the extended
   §7 `cc-config` volume caveat.
3. **Colour escapes in the container** — the source uses raw `\033[` codes via
   `echo -e`. Claude Code's status line renders ANSI; keep as-is. (Low stakes,
   noted for the record.)

## Tasks

### Task 1: Port `statusline.sh` into the module + script unit test [Low]

Add `docker/scripts/statusline.sh`, ported from `/workspace/cc-setup/statusline.sh`:

- Re-verify no host address / hostname / SSH endpoint / key name appears
  (`grep -iE 'shipsmooth\.net|ssh|host|@|\.com|\.net'` — expect only benign
  hits like `display_name`). Record the check in the commit message.
- **Copy byte-for-byte** — no behavioural changes (calibration decision). That
  includes the `pro: …%%` double `%`, the `bc` token formatting (`M`/`k`
  suffixes), the context colour thresholds (≥60 yellow, ≥80 red), the five-hour
  and seven-day rate-limit fields, and `date -d "@$ts"`.

Update `docker/.dockerignore`: add `!scripts` after the `!Dockerfile` line, and
update the leading comment (it currently says "The Dockerfile COPYs nothing from
the build context").

Test — new `docker/src/test/java/io/bitken/ss/docker/StatuslineScriptTest.java`:
run the script via `ProcessBuilder` with a canned JSON fixture on stdin and
assert:
- stdout contains the fixture's `model.display_name`;
- stdout contains the `ctx:`, `pro:`, and `wk:` field labels;
- exit code 0.
(No assertion tidies the `pro: …%%` output — the port is byte-for-byte.)
Skip cleanly (JUnit `assumeTrue`) if `jq`/`bc` are not on `PATH`, so the test is
not a portability trap on a bare CI box.

Commit red first (test referencing a script that does not exist yet), then add
the script.

Verify: `./gradlew :docker:test` green; `grep` scrub clean.

### Task 2: Wire the status line into the Dockerfile + layer-ordering test [Medium]

*Depends-on: 1*

In `docker/Dockerfile`, immediately after the plugin-install `RUN` (line 48) and
before the `ARG SHIPSMOOTH_VERSION` / `LABEL` block:

```dockerfile
# Bake in the Claude Code status line: script at a stable path, plus a MERGED
# settings.json key (the plugin-install step above already wrote enabledPlugins /
# extraKnownMarketplaces into this file — jq must not clobber them).
COPY scripts/statusline.sh /root/.claude/scripts/statusline.sh
RUN chmod +x /root/.claude/scripts/statusline.sh && \
    jq '.statusLine = {"type":"command","command":"~/.claude/scripts/statusline.sh"}' \
       /root/.claude/settings.json > /tmp/settings.json && \
    mv /tmp/settings.json /root/.claude/settings.json
```

Test — new `DockerfileLayoutTest` (or a case added to `DockerModuleContractTest`)
in the `docker/` module: read `docker/Dockerfile` as text and assert
- a `COPY scripts/statusline.sh ` line exists;
- a `jq '.statusLine` line exists;
- the index of the `claude plugin install` line is **less than** the index of
  the `jq '.statusLine` line (merge happens after the file is created);
- the `jq` line does not use `>` redirection straight back onto its input file
  (guards the classic `cmd file > file` truncation mistake — it writes `/tmp`
  then `mv`).

Commit red first (assertions fail against the current Dockerfile), then add the
layer.

Verify: `./gradlew :docker:test` green; `./gradlew build` green; `git diff`
confined to `docker/Dockerfile` + the test.

### Task 3: `smoke.sh` assertions + `DOCKER.md` doc addition [Low]

*Depends-on: 2*

`docker/smoke.sh` — in section 3 ("Labels are not lying"), after the existing
`enabledPlugins` check, add:

```sh
note "checking the baked-in status line survived the settings.json merge"
docker run --rm "$SMOKE_IMAGE" \
  jq -e '.statusLine.command' /root/.claude/settings.json >/dev/null 2>&1 \
  || fail "statusLine.command missing from /root/.claude/settings.json"
docker run --rm "$SMOKE_IMAGE" test -x /root/.claude/scripts/statusline.sh \
  || fail "/root/.claude/scripts/statusline.sh missing or not executable"
```

(The `enabledPlugins` grep immediately above already doubles as the "merge did
not clobber" assertion — call that out in a comment.)

`DOCKER.md`:
- §5 "Launch Claude": one sentence — the image ships a status line showing
  model, context-window usage, and Claude usage limits.
- §7 "Update to a new image version": extend the existing `cc-config` caveat —
  an existing volume also keeps the *old* `settings.json`, so the status line
  (like a newer bundled plugin) only appears after `docker volume rm cc-config`
  + a fresh login.

Update `docker/README.md` if it enumerates what the image contains (add the
status line to that list); otherwise record a minor deviation.

Verify: `bash -n docker/smoke.sh` parses; `DOCKER.md` renders; `grep -i cc-setup`
/ private-repo scrub clean across changed files. Full `docker/smoke.sh` is run
by the user on a Docker host (Docker is unavailable in this container).

## Backlog Issue

Operating the `bitkentech/shipsmooth-claude` image as a distribution channel
(local backlog; no external issue) — continues plan-112 and plan-113. No change
to the shipsmooth plugin's shipped runtime behaviour; this is image-content
follow-through.
