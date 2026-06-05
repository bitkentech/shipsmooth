rootProject.name = "shipsmooth"

// Maven→Gradle migration (plan-71 v2). Modules are included here as each gets
// its build.gradle.kts; a module is only listed once it has a Gradle build, so
// the reactor stays buildable at every step. Target end state (Phase 5):
//   include("core"); include("cli"); include("skills:pkg")
//   include("claude"); include("gemini"); include("packaging"); include("devtools")
include("skills:pkg")
