# cli

The command line tool the shipsmooth [SKILL](https://github.com/bitkentech/shipsmooth/blob/releases/dist/skills/start/SKILL.md) relies on, for plan and task management.

When an agent runs the shipsmooth workflow, the skill shells out to this CLI for
the operations it needs: creating and tagging plans, initialising the task file,
moving tasks through their states etc.

- It depends on [`../core`](../core) for much of its functionality: the workflow, ledger, git
  operations etc. This module is a (thin?) command line surface over that logic. 
  It parses arguments, initializes and invokes the commands, and prints the results.
-  It produces a self-contained `shipsmooth` runtime (`image_<host>` tasks) 
  that gets packaged and shipped.

See [`../DEVELOPMENT.md`](../DEVELOPMENT.md) for more about repo structure and build instructions.
