rootProject.name = "shipsmooth"

// Module graph (plan-79). plugin-model is the shared leaf (Os/Platform/Env/
// PluginModel); skills:pkg renders the SKILL.md files; plugin-resources renders
// the rest (hooks, session-start config, installer, TS hook) and runs Target;
// claude/gemini/codex assemble per-host payloads; packaging zips runtimes/releases.
// Dependency direction: plugin-model <- skills:pkg <- plugin-resources;
// plugin-model <- packaging. See DEVELOPMENT.md for the per-module breakdown.
include("core")
include("cli")
include("claude")
include("codex")
include("gemini")
include("packaging")
include("plugin-model")
include("targets:shared")
include("skills:pkg")
