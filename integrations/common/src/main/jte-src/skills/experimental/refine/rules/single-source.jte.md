@import io.bitken.ss.resources.PluginModel
@param PluginModel model
### Single source of truth
Concepts should be represented using a single source of truth, ideally only at one place, and
referred to by different places, injected either via constructor or DI. Applies to string 
literals as well.
**Good:**
```
/**
 * Provides specific functionality needed by the app
 */
class SomeClass {

  private static final String CLASS_NAME = "SomeClass";
  
  private final Path basePath;
  
  SomeClass(String basePath) {
    this.basePath = new Path(basePath);
  }
  
  public Path getBasePath() {
    return basePath;
  }

  public Path getClassPath() {
    return new Path(basePath, CLASS_NAME);
  }

} 
```
**Bad:**
```
class SomeClass {
  
  public String getBasePath() {
    return "~/.cache/app";
  }

  public String getClassPath() {
    return "~/.cache/app" + "/" + "SomeClass";
  }

} 
```