rootProject.name = "shipsmooth"

// Module graph (plan-79). plugin-model is the shared leaf (Os/Platform/Env/
// PluginModel); skills:pkg renders the SKILL.md files; harness:shared renders the
// rest (hooks, session-start config, installer, TS hook) and runs Target;
// harness:{claude,gemini,codex} assemble per-host payloads; packaging zips
// runtimes/releases. Add a new agent harness as harness:<name>.
// Dependency direction: plugin-model <- skills:pkg <- harness:shared <-
// harness:{claude,gemini,codex}; plugin-model <- packaging.
// docker: shipsmooth-claude image build tooling (plan-113), folded in from the
// standalone cc-setup repo. No internal deps — it consumes the *published*
// plugin, not the build graph. Its outward task (buildAndPush) is wired-only.
// See DEVELOPMENT.md for the per-module breakdown.
include("core")
include("cli")
include("plugin-model")
include("skills:pkg")
include("harness:shared")
include("harness:claude")
include("harness:gemini")
include("harness:codex")
include("harness:opencode")
include("packaging")
include("docker")
