# First-run Handshake (reference)

When a `$SS` command reports that state is not set up — `store info --json` returns a
`status` other than `ready`, or a state-dependent command exits with a
`status:"needs-decision"` / `status:"unresolvable"` JSON line (exit 10 / 11) — do **not**
treat it as a normal error. Run this handshake. The CLI never prompts on stdin; presenting
the choice and capturing a real human answer is the skill's job.

**`needs-decision` → ask the user, then act:**

1. **Show the CLI's `prompt` field verbatim.** The `needs-decision` JSON carries a ready-to-
   display `prompt` (the question, each option with its real resolved path, the recommended
   one marked, and — for a clean first run — a note that a different folder may be given).
   Render it as-is; do not rewrite it or invent paths.
2. **Wait for a real answer. Never auto-pick.** The CLI only ever creates a state location
   on a fresh human "yes" (consented creation). Default toward the recommended option, but
   the user may pick the alternative or name a different folder.
3. **Re-invoke the CLI to act** on the choice, matching the `choice` token from the chosen
   option:
   ```bash
   # separate-dir (recommended) — accept the proposed folder:
   $SS store init --type separate-dir --json
   # separate-dir — a different folder the user named:
   $SS store init --type separate-dir --path <user's folder> --json
   # keep it inside this repo:
   $SS store init --type same-repo --json
   # a configured separate-dir folder went missing — recreate it:
   $SS store init --type recreate --path <path from the option> --json
   ```
   `store init` creates the chosen location, writes the config entry, and prints the
   `ready` shape — read its `plansDir` for where plan files now live.

**`unresolvable` → stop.** The situation cannot be settled automatically (e.g. a legacy
`.agents/` tree). Show the JSON `message` to the user and stop; do not try to fix it. The
message says what to correct by hand.

**Already settled → silent.** When resolution is `ready`, none of this applies; the command
just runs. Steady state never re-prompts.
