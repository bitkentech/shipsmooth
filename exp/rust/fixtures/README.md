# Golden fixture corpus (plan-102 Task 1, gw sequence added in plan-107)

Reference artifacts produced by the **Java** shipsmooth CLI (0.3.36), against
which the Rust port is verified. `xml/` holds `plan-{N}-tasks.xml` snapshots;
`transcripts/` holds stdout captures with exit codes for the machine contracts.

Regenerate with `./generate.sh` (uses the `$SS` runtime, override via `SS=`).
Regenerated files differ in timestamps, absolute paths, and dates — compare
structurally, not byte-wise. The committed corpus is the reference.

## xml/

| File | What it pins |
|---|---|
| `00-real-plan-96.xml`, `00-real-plan-97.xml` | Authentic files from real work in this repo (current, post-plan-90 format) |
| `01-fresh-init.xml` | `plan init` output: risks high/medium/low/empty, markdown-derived `<depends-on>` (single and multi-id), empty containers |
| `02-rich.xml` | Every feature: all 7 task statuses, comments (XML-escapables + unicode), both deviation types, set-commit, `task add` with depends-on, project updates incl. `blocked` |
| `03-status-in-review.xml`, `04-status-complete.xml` | Plan-status transitions |
| `05-minimal-abandoned.xml` | Smallest valid file + `abandoned` plan status |
| `06-unknown-ext-input.xml` | **Hand-extended** (see generate.sh): unknown `xs:any` elements (`<meta-ext>` with attribute + nested child in metadata, `<future-field>` with attribute in a task) inserted into 04 |
| `07-unknown-ext-after-java-rewrite.xml` | 06 after a Java CLI read-modify-write (`task comment`) — pins that JAXB PRESERVES unknown lax elements and how it reformats them |
| `gw/step-00…17-*.xml` | plan-107: one snapshot **after every TaskStore mutation** (statuses, comments, deviations, set-commit, task add, project updates); the filename encodes the operation. The gw golden-replay harness re-applies the same sequence through the Rust TaskStore and byte-diffs each step (timestamps normalised). depends-on replace/remove has no CLI command — pinned by the ported TaskStoreTest instead |

## transcripts/

- `gate-clean-first-run.json` + `.exit` — the resolution-gate JSON contract on
  an unsettled project (exit **10**, `needs-decision`/`clean-first-run` with
  both options). Absolute paths inside are run-specific.
- `store-info-unsettled.json` / `store-info-ready.json` /
  `store-init-same-repo.json` — the `--json` store contracts.
- `plan-resume-rich.txt` — human-readable resume rendering of the rich plan.
- `error-invalid-status.{out,err,exit}` — stderr/exit (2) for a bad enum value.

## Format facts the Rust writer must reproduce (observed in this corpus)

- XML decl: `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>`.
- 4-space indentation.
- Empty **string** elements render open+close (`<commit></commit>`); empty
  **containers** self-close (`<comments/>`).
- Text escaping: `&` `<` `>` escaped; `"` and `'` NOT escaped in text content.
  Unicode written raw (UTF-8).
- Timestamps: `2026-07-17T14:02:45.471+05:30` (millis + zone offset); dates
  plain `2026-07-17`.
- Unknown `xs:any` elements survive read-modify-write and get re-indented into
  the pretty-printed layout (compare 06 → 07).
