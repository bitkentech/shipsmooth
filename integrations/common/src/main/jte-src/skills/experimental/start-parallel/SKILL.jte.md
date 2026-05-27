@import io.bitken.ss.resources.PluginModel
@param PluginModel model
@if(!model.skillFrontmatter().isEmpty())${model.skillFrontmatter()}@endif

# ${model.skillName()} — Agent Coding Workflow (Parallel Execution)
@template.skills._partials.base-workflow(model = model)
@template.skills._partials.parallel-execution(model = model)