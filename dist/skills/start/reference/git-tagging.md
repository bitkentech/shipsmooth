# Git Tagging Convention (reference)

Every time a plan file is committed, immediately create and push a version tag:

```bash
$SS plan tag --plan {N} --kind version
# prints: git push origin plan-{N}-v{K}  — run that line to push
```

On clean completion:
```bash
$SS plan tag --plan {N} --kind complete
# prints: git push origin plan-{N}-complete
```

On abandonment:
```bash
$SS plan tag --plan {N} --kind abandoned
# prints: git push origin plan-{N}-abandoned
```

Tag naming: `plan-{N}-v{version}` for iterations, `plan-{N}-complete` for clean closeout, `plan-{N}-abandoned` for abandonment. `plan tag --kind version` refuses to re-tag if the computed tag already exists — commit more changes first.
