@param io.bitken.ss.resources.PluginModel model

Run `git branch -l 'agent-work/*' --format '%(refname:short)'` to list agent-work branches. For each branch printed, run these two commands, substituting the branch name for `{branch}`:
```bash
git merge-base HEAD {branch}
```
Capture the SHA printed (call it `{base-sha}`), then run:
```bash
git diff --name-only {base-sha}..{branch}
```