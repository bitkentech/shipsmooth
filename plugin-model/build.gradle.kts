plugins {
    id("shipsmooth.java-conventions")
}

// plugin-model is the leaf module of the plugin-build graph: the shared value
// types (Os, Platform, Env, PluginModel) that the skill renderer, the
// plugin-resources renderers, and packaging's PackageRuntime all build on.
// It deliberately has NO shipsmooth module dependencies and no third-party deps
// beyond the JUnit test stack inherited from java-conventions — the four types
// reference only java.* . Keeping them here lets `packaging` depend on this tiny
// module for `Os` instead of pulling in the whole skills-rendering module
// (plan-79).
