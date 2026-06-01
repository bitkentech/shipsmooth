@import io.bitken.ss.resources.PluginModel
@param PluginModel model
### Constructor
Almost everything should be constructed using a constructor, using dependency injection where
appropriate. The constructor should consist mostly of assignments, private static method calls,
and fail-fast w.r.t initializing things.  
**Good:**
```
/**
 * Provides specific functionality needed by the app
 */
class SomeClass {

  private final Class1 obj1;
  private final Class2 obj2;
  private final Class3 obj3;
   
  SomeClass(Class1 obj1, Class2 obj2) {
    this.obj1 = obj1;
    this.obj2 = obj2;
    this.obj3 = createObj3(obj2);
  }
  
  private static Class3 createObj3(Class2 obj2) throws IOException {
    ... elided
  }
}
```
**Bad:**
```
class SomeClass {

  private Class1 obj1;
  private Class2 obj2;
  private Class3 obj3;
   
  SomeClass(Class1 obj1, Class2 obj2) {
    this.obj1 = obj1;
    this.obj2 = obj2;
    if (obj2.isDesktop()){
      LOG.info("Creating obj3 in this way because obj2 is something");
      this.obj3 = obj2.createDesktopComponent(true);
    } else {
      LOG.info("Creating obj3 in this way because obj2 is something else");
      this.obj3 = obj2.createWebComponent(false);
    }
  }
 }
```
**Why this matters:** The Bad constructor mixes branching, logging, and two different
construction paths inline, so the decision logic is trapped where it can't be tested or
reused and the constructor is hard to read at a glance. The Good version pushes that logic
into a named `private static createObj3(...)` helper: the constructor stays a flat list of
assignments, the construction decision gets a name and a single tested location, and
fail-fast still happens at construction time.