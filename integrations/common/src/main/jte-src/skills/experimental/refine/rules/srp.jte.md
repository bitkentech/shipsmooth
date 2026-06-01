@import io.bitken.ss.resources.PluginModel
@param PluginModel model
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