@import io.bitken.ss.resources.PluginModel
@param PluginModel model
### Method ordering
public methods should be top, after constructor. private methods go after that. When ordering
private methods, try to put callers first and callees next, but not always possible if private methods
call each other mutually. 