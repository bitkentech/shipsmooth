@import io.bitken.ss.resources.PluginModel
@param PluginModel model
### Method structure
Most methods should look like this: mostly linear flow of control, sensible guard clauses,
early returns:
```
private Optional<Integer> fn1(Class1 obj1, Class2 obj2) {
  if (obj1 === null) {
    return empty();
  }
  
  if (obj2 === null) {
    return empty();
  }
    
  Class3 obj3 = fn2(obj1);
  if (obj3.isSomeCondition()) {
    return process1(obj2); // where process1 will be a 4-5 lines private method
  }
  
  return process2(obj2); where process2 will be a 4-5 lines private method
}
```