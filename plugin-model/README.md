# plugin-model

A tiny module containing shared value types used during rendering and
packaging of shipsmooth's plugins. plugin-model *must not* depend on other 
shipsmooth modules, so that anything that needs these types can pull them
in without bringing in rendering or packaging machinery.

## See also

- [`../harness/`](../harness): the renderers that consume these types
- [`../DEVELOPMENT.md`](../DEVELOPMENT.md) : for more about repo structure and build instructions
