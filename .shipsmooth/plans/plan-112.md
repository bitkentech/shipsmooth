# plan-112 — README: add "Docker (Claude Code)" install method + DOCKER.md

## Context

`bitkentech/shipsmooth-claude` is a public Docker image (Ubuntu + Node + Claude
Code with the shipsmooth plugin pre-installed) that lets someone run a sandboxed
coding agent with one `docker run`. It is built from a **private** maintainer
repo; only the image on Docker Hub is public, so nothing in shipsmooth's docs
may link or name that repo.

The shipsmooth `README.md` **Installation** section currently lists four
methods, each a short `#### N. <harness>` subsection:

1. Claude Code
2. Gemini CLI
3. Codex CLI
4. OpenCode

followed by a harness-agnostic **"#### What gets installed"** note (the
`SessionStart` hook that downloads the jlink runtime into `~/.cache/shipsmooth/`).

**Goal (user's words):** "Update README to include docker image based usage as
described in `/workspace/cc-setup`. Have it as a method 2 in the installation
section (call it: Docker (Claude Code))."

**Refined with the user (2026-09-02):**

- Insert `#### 2. Docker (Claude Code)` immediately after `#### 1. Claude Code`;
  renumber the rest — Gemini CLI → 3, Codex CLI → 4, OpenCode → 5.
- The README subsection is **just a ~5-line quickstart** (pull → run → exec →
  launch).
- The fuller material from the source README moves into a new **`DOCKER.md`** at
  the shipsmooth repo root; the quickstart links to it.
- **Do not mention or link the private maintainer repo** anywhere. No
  "maintainer / building the image" section in `DOCKER.md`.

### Source material

Distilled from `/workspace/cc-setup/README.md` and `.../Dockerfile` (verified
2026-09-02):

- Image: `bitkentech/shipsmooth-claude:latest`. Also published as immutable
  `claude-<cc-version>-ss-<ss-version>` and dated tags, alongside `latest`.
- The image bakes in the plugin (`claude plugin install shipsmooth --scope
  user`) but **not** the jlink runtime — the `SessionStart` hook still runs on
  the first `claude` launch *inside the container* and downloads it to
  `/root/.cache/shipsmooth/` (needs network). So the README's existing
  "What gets installed" note stays accurate for Docker users.
- Run flow: `docker pull` → `docker run -dit --name cc -v "$(pwd):/workspace"
  -v cc-config:/root/.claude <image>` → `docker exec -it cc bash` →
  `gh auth login` + `git config --global user.name/email` → `claude` (headless
  login: prints a URL, approve in browser, paste code back) → `/shipsmooth:start`.
- `-dit` flag rationale: detached + TTY held open so the container survives
  between `docker exec`s; drop `-d` for a session that ends on exit; needs `-t`
  or bash exits immediately.
- The `cc-config` named volume persists Claude's config + credentials
  independently of the container, so login survives `docker rm`. Docker seeds it
  from the image on first run (plugin already present), then it persists.
  Caveat: the volume keeps the plugin version from the image it was *first*
  created against — `docker volume rm cc-config` to pick up a newer bundled
  plugin (needs a fresh login).
- Lifecycle: `docker stop cc` / `docker start cc` / `docker rm -f cc` (volume
  survives). Update: `docker pull …latest` → `docker rm -f cc` → re-run.
- Remote box: same steps over `ssh`, just ensure Docker is installed.
- Troubleshooting (from source): container exits immediately (missing `-t`);
  no network inside container (test with `docker run --rm ubuntu:26.04 apt-get
  update`); root-owned files on host (`sudo chown -R "$(id -u):$(id -g)" .`);
  Apple Silicon runs the `linux/amd64` image under emulation (slower).
- Version labels: `docker inspect --format '{{json .Config.Labels}}'
  bitkentech/shipsmooth-claude:latest` — `io.bitken.ss.claude-code.version`,
  `io.bitken.ss.shipsmooth.version`, `org.opencontainers.image.version`
  (compound tag), `org.opencontainers.image.revision` (git commit). Claude Code
  self-updates at runtime, so the label is the *starting* version.
- **Omit** from `DOCKER.md`: the source README's "Building the image (maintainer
  only)" section and its `org.opencontainers.image.source` provenance (both name
  the private repo). Rephrase the labels table row for
  `org.opencontainers.image.revision` as "git commit the image was built from"
  without naming the repo.

### Design decisions

1. **`DOCKER.md` at the repo root**, alongside `DEVELOPMENT.md` /
   `EXPERIMENTAL.md` (both root-level, both linked from `README.md`) — matches
   the existing convention for supplementary guides.
2. **README quickstart stays ~5 lines + one code block**, matching the sibling
   install methods; everything else is "see [DOCKER.md](DOCKER.md)".
3. **Heading style:** `#### 2. Docker (Claude Code)` — identical level and
   numbering to the sibling methods, so it renders smaller than `##
   Installation` (a constraint from plan-101).
4. **No renumbering fallout elsewhere.** Verified 2026-09-02: the only place in
   the repo referencing these method numbers is the `README.md` Installation
   section itself (`grep -rn` across `README.md` + `docs/`). The website
   (`shipsmooth.net/install/`) is a separate repo, out of scope.
5. **`DOCKER.md` is self-contained and public-safe** — no private-repo mention,
   no credentials, no maintainer/build instructions.

### TDD invariant

Docs-only change, no code surface — "tests precede implementation" applies "as
far as possible" (per plan-101's precedent). Verification: Markdown renders with
correct numbering, every link resolves (`README.md` → `DOCKER.md`, and
`DOCKER.md`'s internal + external links), and the inlined `docker` commands
match `/workspace/cc-setup/README.md` verbatim. No integration-test preamble.

## Open questions (resolve during Phase 1 calibration)

1. **`DOCKER.md` scope** — include the full troubleshooting + version-labels
   detail from the source (Task 1 as drafted), or keep `DOCKER.md` leaner and
   let the Docker Hub image description carry troubleshooting?
2. **Cross-link from `DOCKER.md` back to the main README** install section? (Low
   stakes; proposed: yes, one line at the top.)

## Tasks

### Task 1: Add `DOCKER.md` — the full Docker usage guide [Low]

Create `DOCKER.md` at the repo root, adapted from `/workspace/cc-setup/README.md`
but self-contained and public-safe. Sections:

- **What this is** — the `bitkentech/shipsmooth-claude` image: Ubuntu + Node +
  Claude Code + shipsmooth plugin, pull-and-run sandbox; edits land on the host.
- **Pull / run / enter** — the same three commands as the README quickstart,
  with the flag table (`-dit`, `--name cc`, the two `-v` mounts) explained.
- **Set up git** — `gh auth login` (choose HTTPS), `git config --global
  user.name`/`user.email`.
- **Launch Claude** — first-run headless login (prints URL, approve, paste code
  back); login lands in `cc-config`, survives restarts and `docker rm`; no
  credentials are baked into the image. Then `/shipsmooth:start`.
- **Lifecycle** — `docker stop`/`start`/`rm -f`; the `cc-config` volume is not
  deleted by `docker rm`.
- **Update to a new image version** — `docker pull …latest` → `docker rm -f cc`
  → re-run; `docker volume rm cc-config` to pick up a newer bundled plugin
  (needs fresh login).
- **Running on a remote box** — same steps over `ssh` with Docker installed.
- **Troubleshooting** — container exits immediately (missing `-t`); no network
  (the `ubuntu:26.04 apt-get update` test); root-owned files
  (`sudo chown -R "$(id -u):$(id -g)" .`); Apple Silicon emulation note.
- **Which version am I running?** — the `docker inspect … .Config.Labels`
  command and a labels table (drop the `image.source` row; reword the
  `image.revision` row to not name a repo). Note Claude Code self-updates, so
  the label is the starting version; every build also gets an immutable
  `claude-<v>-ss-<v>` tag and a dated tag.
- (proposed) one line at the top linking back to the README Installation
  section.

Explicitly **not** included: any mention of the private maintainer repo, the
"Building the image" section, credentials, or build tooling.

Verify: `DOCKER.md` renders; the `docker` commands match
`/workspace/cc-setup/README.md` verbatim; no occurrence of the private repo
name/URL (`grep -i` for `cc-setup` and the GitHub org); all links resolve.

### Task 2: Add the README quickstart (method 2) + renumber 3–5 + reconcile prose [Low]

*Depends-on: 1*

In `README.md`, **Installation** section:

- Insert between `#### 1. Claude Code` (ends after the Windows note) and
  `#### 2. Gemini CLI`:

  ```markdown
  #### 2. Docker (Claude Code)

  Prefer a ready-made sandbox? The `bitkentech/shipsmooth-claude` image ships
  Claude Code with the shipsmooth plugin already installed. From the directory
  you want Claude to work in:

  ```sh
  docker pull bitkentech/shipsmooth-claude:latest

  docker run -dit --name cc \
    -v "$(pwd):/workspace" \
    -v cc-config:/root/.claude \
    bitkentech/shipsmooth-claude:latest

  docker exec -it cc bash
  ```

  Inside the container, set up git and run `claude` (first launch prints a login
  URL), then `/shipsmooth:start`. Full guide — git auth, lifecycle, updates,
  remote hosting, troubleshooting: **[DOCKER.md](DOCKER.md)**.
  ```

- Renumber: `#### 2. Gemini CLI` → `#### 3.`, `#### 3. Codex CLI` → `#### 4.`,
  `#### 4. OpenCode` → `#### 5.`. Leave `#### What gets installed` unnumbered.
  Do not touch the body text of the Gemini/Codex/OpenCode subsections.

- **Reconcile surrounding prose** (minimal):
  - "#### What gets installed" — still accurate for Docker (runtime download
    happens inside the container on first `claude`). Add at most one
    parenthetical; skip if it reads fine as-is.
  - "Uninstall" — add a one-line note that for the Docker method removal is
    `docker rm -f cc` (+ `docker volume rm cc-config` to drop the stored login),
    or a pointer to `DOCKER.md`, rather than the `rm -rf ~/.cache/...` steps.
  - "How to start using the workflow" intro — already method-agnostic; confirm,
    likely no change.

  If a spot needs no change, record it as a minor deviation.

Verify: full `README.md` renders; numbering reads 1 → 2 (Docker) → 3 → 4 → 5 →
"What gets installed"; `README.md` → `DOCKER.md` link resolves; the `docker`
commands are byte-identical to `/workspace/cc-setup/README.md` lines 20–24 and
37; `git diff` is confined to the Installation and Uninstall sections.

## Backlog Issue

None recorded — backlog concept is being retired (per plan-101 / PB-371);
recent docs plans leave this empty. This is documentation follow-through for the
public `bitkentech/shipsmooth-claude` image, with no change to shipped runtime
behaviour.
