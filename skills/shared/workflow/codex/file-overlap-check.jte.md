@param io.bitken.ss.resources.PluginModel model

```bash
for branch in $(git branch -l 'agent-work/*' --format '%(refname:short)'); do
  echo "=== $branch ==="; git diff --name-only $(git merge-base HEAD $branch)..$branch
done
```