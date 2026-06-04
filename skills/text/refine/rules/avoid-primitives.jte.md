@import io.bitken.ss.resources.PluginModel
@param PluginModel model
### Prefer objects over "primitive obsession"
Especially if the same two or more primitively typed variables are always seen together.
Check if an appropriate class type already exists in the codebase, or in libraries / JDK.
**If no suitable type exists, you are explicitly authorized and expected to create one** —
a `record`, a `private record`, or a package-private class — rather than threading the raw
primitives through. Do not let the absence of an existing type push you back toward passing
loose `String`s/`int`s around.
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