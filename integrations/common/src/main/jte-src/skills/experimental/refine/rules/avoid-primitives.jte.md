@import io.bitken.ss.resources.PluginModel
@param PluginModel model
### Prefer objects over "primitive obsession"
Especially if the same two or more primitively typed variables are always seen together.
Check if an appropriate class type already exists in the codebase, or in libraries / JDK.
**Good:**
```
public boolean canOpen(String dir, String filename) {
  Path fullPath = new Path(dir, filename);
  return !notExists(fullPath);
}
```

**Bad:**
```
public boolean canOpen(String dir, String filename) {
  if (!notExists(dir, filename)) {
    return false;
  }
  return true;
}
```