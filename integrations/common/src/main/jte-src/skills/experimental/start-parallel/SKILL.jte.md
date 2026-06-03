@import io.bitken.ss.resources.PluginModel
@param PluginModel model
@if(!model.skillFrontmatter().isEmpty())${model.skillFrontmatter()}@endif

# ${model.skillName()} — Agent Coding Workflow (Parallel Execution)
@template.skills.shared.base-workflow(model = model)
@template.skills.shared.parallel-execution(model = model)