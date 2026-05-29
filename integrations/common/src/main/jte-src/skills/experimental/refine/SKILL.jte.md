@import io.bitken.ss.resources.PluginModel
@param PluginModel model
@if(!model.skillFrontmatter().isEmpty())${model.skillFrontmatter()}@endif

# ${model.skillName()} — Refine your code
## Execution Contract
When this skill is invoked, the user will provide one or more parameters under an 
"Apply to" block/section (Files, Folders, Diffs, or Functional requirements).

Above all, be very ambitious, especially when the change is only across a handful of files.
The code can look *very different* after you're done with it. Only the logic should continue
to be the same. When changing, do not attempt to patch the files in place. Instead, perform a
clean-slate re-derivation: treat the old code purely as a requirements document.

You must:
1. Identify the target package boundary (`cli/`, `conf/`, `workflow/`) for the provided parameters.
2. If a folder or file is passed that belongs to a legacy unorganized package, execute a clean-slate
 re-derivation to move it to its correct target package
3. Prioritize architectural intent and dependency direction over minor, local code patches
4. Ask if the user wants a high level preview of suggested changes. If yes, show preview 
with *actual* fragments of code inter-mixed with pseudo-code (a "BEFORE" version and "AFTER" version),
with bits of prose. Do *not* show only English prose explanations!

### Single Responsibility Principle
- For methods: A method or function should have only one responsibility, usually indicated 
by the name itself
- How to validate this for classes: 1) Guess its publicly facing functionality based on its 
name, and the guess should turn out largely correct 2) Anything you missed should be conveyable in 2-3 lines 
of comments above the class name. 3) Is it taking too many constructor parameters 4) Are its public 
facing method names wildly different? 4) If you're receiving another object as collaborator,
and using only one slice of its overall cohesive functionality, then you can receive it as an
interface that only implements that part (also called Interface Segregation principle).  
**Good**:
```
/**
 * Geography related information about current execution context.
 */
interface Geo {  boolean contains (City city); }
class CurrentContext implements Geo, Demography, Platform {
}
/**
 * Validate customer's location info before servicing them.
 */
class ValidateCustomerLocation {
   private final Geo geo;
   ValidateCustomer(Geo geo) { /** Not passing full CurrentContext here **/
     this.geo = geo;
   }
   
   public boolean validate(Customer customer) {
     return geo.contains(customer.city());
   }
}
```
**Bad**:
```
class CurrentContext {
  boolean containsCity(City city) { ... elided }
  boolean containsAgeGroup(Age age) { ... elided }
  boolean supportsPlatform(Platform platform) { ... elided }
}

class CustomerUtils {
   private final CurrentContext context;
   private final DisplayFormatter fmt;
   
   CustomerUtils(CurrentContext context, DisplayFormatter fmt) {
     this.context = context;
     this.fmt = fmt;
   }
   
   public boolean validateCity(Customer customer) {
     return context.containsCity(customer.city());
   }
   
   public void displayDetails(Customer customer) {
     System.out.println(fmt.format(customer.name(), customer.age()));
   }
}
```

### Prefer Rich Domain Models over Anemic Objects, whenever possible
Classes should have instance methods that *do* things. They should not simply enclose data fields, 
and then other distinct "Doer" classes (e.g: SomethingProcessor, SomethingFormatter) do those actions, 
receiving either the entire object or its fields as parameters. The logic in methods should rely on 
parameters for immediate information and instance fields for permanent/long term information. 
Methods do one of:
i) Almost always just return a value calculated by combining information from the parameter
with fields and local methods.
ii) Invoke other methods (either on the received parameter, or other local methods,
or on an instance field with current parameter(s) passed along)
iii) Update internal state (very rare, because most fields are final)

**Good:**
```
class Order {
  private final Vendor vendor;
  private final Rejections rejections;
  private Optional<Item> item;
  
  Order(Vendor vendor, Rejections rejections) {
    this.vendor = vendor;
    this.rejections = rejections;
    item = empty();
  }

  public void process(CustomerRequest req) {
    if (item.isNotEmpty()) {
      return;
    }
    
    if (!isValid(req)) {
      rejections.add(req);
      return;
    }
    
    Item item = new Item(req.getName(), req.getQuantity());
    vendor.inform(item);
    this.item = Optional.of(item); 
  } 
  
  private boolean isValid(CustomerRequest req) {
   ... elided
  }
}
```

**Bad::**
```
class Order {
  private Optional<Item> item;
  Order() {
    this.item = empty();
  }
  
  public Optional<Item> getItem() {
    return item;
  }
  
  public void setItem(Item item) {
    this.item = Optional.of(item);
  }
}
class OrderProcessor {
  private final OrderValidator validator;
  private final OrderRejector rejector;
  
  public OrderProcessor(OrderValidator validator, OrderRejector rejector) {
    this.validator = validator;
    this.rejector = rejector;
  }

  public void processOrder(CustomerRequest req, Order order) {
    if (validator.isValid(req)) {
      Item item = new Item(req.getName(), req.getQuantity());
      vendor.inform(item);
      order.setItem(item);
    } else {
      rejector.handle(req);
    }
  }
}
class OrderValidator {
  public boolean isValid(CustomerRequest req) {
    ... elided 
  }
}
```

### Single source of truth
Concepts should be represented using a single source of truth, ideally only at one place, and
referred to by different places, injected either via constructor or DI.  
**Good:**
```
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

### Class fields
Class fields should be private final and assigned to in constructor unless there's a strong
reason not to.

### Constructor
Almost everything should be constructed using a constructor, using dependency injection where
appropriate. The constructor should consist mostly of assignments, private static method calls,
and fail-fast w.r.t initializing things.  
**Good:**
```
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

### Prefer objects over "primitive obsession"
Especially if the same two or more primitively typed variables are always seen together.
Check if an appropriate class type already exists in the codebase, or in libraries / JDK.
**Good:**
```
public boolean canOpen(String dir, String filename) {
  Path fullPath = new Path(dir, filename);
  if (!notExists(fullPath)) {
    return false;
  }
  return true;
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

### Static methods
Static methods should be very rare in Java (mainly for main()). Exception is for private static methods
used in constructor where you need to create stuff based on constructor parameters.

### Method ordering
public methods should be top, after constructor. private methods go after that. When ordering
private methods, try to put callers first and callees next, but not always possible if private methods
call each other mutually. 

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

### Method length
Methods should not be more than 10-15 lines in length. Break it down with descriptive names that
explain what they do.

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

### File length
Java class files should not be more than 200 lines in length, excluding imports and 
the top-level class name declaration and class level Javadoc comments.

### if nesting
Never more than 2 levels of nesting in a single method. Extract third level to separate method
with nice name.

### if blocks length
If the code in an if-block is 5 lines or more, extract method with descriptive name.

### Pasting below an example of good and bad file structure for a codebase.  
**Good:**
```
  cli/
    AddComment.java
    AddDeviation.java
    Integrate.java
  conf/
    AppComponents.java
    FeatureFlags.java
    ServicesModule.java
    ShipsmoothDataLocator.java
  gw/
    GitTags.java
    TaskStore.java
  ledger/
    Event.java
    EventLedger.java
    EventType.java
    ObjectStore.java
  workflow/
    WorkflowService.java
    WorkflowServiceImpl.java
    integration/
      IntegrationDefaults.java
      IntegrationLedger.java
  module-info.java
```
**Bad:**
``` 
 TasksCli.java
  AgentsLayout.java
  commands/
    AddCommentCommand.java
    AddDeviationCommand.java
    IntegrateCommand.java
  di/
    AppComponents.java
    ServicesModule.java
  integration/
    IntegrationDefaults.java
    IntegrationLedger.java
  ledger/
    Event.java
    EventType.java
    LedgerService.java
    ObjectStore.java
  stability/
    FeatureFlags.java
  workflow/
    WorkflowService.java
    WorkflowServiceImpl.java
  module-info.java
```