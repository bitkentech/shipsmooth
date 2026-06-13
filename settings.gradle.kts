rootProject.name = "shipsmooth"

// Module graph (plan-79). plugin-model is the shared leaf (Os/Platform/Env/
// PluginModel); skills:pkg renders the SKILL.md files; targets:shared renders the
// rest (hooks, session-start config, installer, TS hook) and runs Target;
// targets:{claude,gemini,codex} assemble per-host payloads; packaging zips
// runtimes/releases. Add a new host as targets:<name>.
// Dependency direction: plugin-model <- skills:pkg <- targets:shared <-
// targets:{claude,gemini,codex}; plugin-model <- packaging.
// See DEVELOPMENT.md for the per-module breakdown.
include("core")
include("cli")
include("plugin-model")
include("skills:pkg")
include("targets:shared")
include("targets:claude")
include("targets:gemini")
include("targets:codex")
include("packaging")
