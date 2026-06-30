@import io.bitken.ss.resources.PluginModel
@param PluginModel model
@if(!model.skillFrontmatter().isEmpty())${model.skillFrontmatter()}@endif
@template.shared.base-workflow(model = model)