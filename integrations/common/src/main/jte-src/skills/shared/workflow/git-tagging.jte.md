@import io.bitken.ss.resources.PluginModel
@param PluginModel model
## Git Tagging Convention

Every time a plan file is committed and pushed, immediately create and push a version tag:

```bash
# After committing a plan file change:
git tag plan-07-v1
git push origin plan-07-v1

# Subsequent revisions:
git tag plan-07-v2
git push origin plan-07-v2

# On clean completion:
git tag plan-07-complete
git push origin plan-07-complete

# On abandonment (tag the deletion commit too):
git tag plan-07-abandoned
git push origin plan-07-abandoned
```

Tag naming: `plan-{N}-v{version}` for iterations, `plan-{N}-complete` for clean closeout, `plan-{N}-abandoned` for abandonment.

### Automate with lefthook

Commit a hook so tagging fires automatically on every push, regardless of whether a human or agent made the commit:

```yaml
# lefthook.yml
pre-push:
  commands:
    auto-tag-plans:
      run: |
        # Detect if any .agents/plans/ file changed in the push
        if git diff --name-only HEAD~1 HEAD | grep -q '^\.agents/plans/'; then
          PLAN=$(git diff --name-only HEAD~1 HEAD | grep '^\.agents/plans/' | head -1)
          PLAN_ID=$(echo "$PLAN" | grep -oP 'plan-\d+')
          # Find next version number
          LATEST=$(git tag -l "${"${"}PLAN_ID}-v*" | sort -V | tail -1)
          if [ -z "$LATEST" ]; then
            NEXT="${"${"}PLAN_ID}-v1"
          else
            N=$(echo "$LATEST" | grep -oP '\d+$')
            NEXT="${"${"}PLAN_ID}-v$((N+1))"
          fi
          git tag "$NEXT"
          git push origin "$NEXT"
          echo "Auto-tagged: $NEXT"
        fi
```

Install lefthook if not present: `npm install -g lefthook && lefthook install`

---
