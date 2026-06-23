# Plan 08 — Reset release tag from v0.9 to v0.1.0

## Narrative

The current release tag `v0.9` misrepresents the project's maturity — 0.9 implies
near-production-ready software. The project has no public visibility and no users, making
this the right time to reset. The goal is to delete `v0.9` locally and remotely, then
create `v0.1.0` pointing to current `main` HEAD.

The GitHub Release associated with `v0.9` will be deleted manually by the user.

## Delivers

- [PB-183 — Reset release tag from v0.9 to v0.1.0](https://linear.app/pb-default/issue/PB-183/reset-release-tag-from-v09-to-v010)

## Tasks

### Task 1 — Delete v0.9 tag (on feature branch)

1. Delete local tag: `git tag -d v0.9`
2. Delete remote tag: `git push origin :refs/tags/v0.9`

No code changes. No tests required.

### Task 2 — Create v0.1.0 tag (after merging to main)

After the PR for this branch is merged to main:

1. Switch to main and pull: `git checkout main && git pull`
2. Create new tag on main HEAD: `git tag v0.1.0`
3. Push new tag: `git push origin v0.1.0`

## Verification

- `git tag -l` shows `v0.1.0`, no `v0.9`
- `git ls-remote --tags origin` confirms same on remote
- User manually deletes the `v0.9` GitHub Release via GitHub UI
