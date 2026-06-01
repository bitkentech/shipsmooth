@import io.bitken.ss.resources.PluginModel
@param PluginModel model
### Class Structure
Most classes should consist of a constructor at the top, which assigns to mostly private final
fields based on passed in parameter, or using "new" keyword if parameter passing/dependency 
injection is not feasible. The constructor should consist mostly of assignments, private 
static method calls, and fail-fast w.r.t initializing things. Bulk of the class should be 
instance methods - a handful public, any no. of private helpers. All the "new" invocations 
in a class should be attempted to be moved into constructor.
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
  
  public int calculateSomething(Param param1) {
    return helper1(obj1, param1);
  }
  
  private int helper1(Class1 obj1, Param param1) {
     ... elided
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
    this.obj3 = new Class3();
  }
  
  public static int calculateSomething(Param1 param) {
    return create(obj1, obj2).helper1(obj1, param);
  }
  
  private int helper1(Class1 obj1, Param param1) {
     ... elided
  }
  
  public static SomeClass create(Class1 obj1, Class2 obj2) {
    return new SomeClass(obj1, obj2);
  }
  
}
```