@import io.bitken.ss.resources.PluginModel
@param PluginModel model
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
/**
 * Represents an order to be processed by the system.
 */
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