@import io.bitken.ss.resources.PluginModel
@param PluginModel model
### Ternaries and boolean expressions
Occasionally it makes sense to use a simple ternary when assigning a value, provided the
expressions are very short, or invoke highly readable method names. Similarly, boolean returns.
**Good:**
```
public boolean canCreate(User user, String dir, String filename) {
  Role role = user.isActive() ? user.getRole() : Role.INACTIVE;
  return role.isAdmin() && !alreadyExists(new Path(dir, filename));
}
```
**Bad:**
```
public boolean canCreate(User user, String dir, String filename) {
  Role role;
  if (user.isActive()) {
    role = user.getRole();
  } else {
    role = Role.INACTIVE;
  }
  if (role.isAdmin()) {
    if (alreadyExists(new Path(dir, filename))) {
      return false;
    } else {
      return true;
    }
  } else {
    return false;
  }
}
```