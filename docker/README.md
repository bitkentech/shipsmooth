# `docker/` — the `shipsmooth-claude` sandbox image

Build tooling for **`bitkentech/shipsmooth-claude`**: Ubuntu + Node + [Claude
Code](https://www.anthropic.com/claude-code) with the shipsmooth plugin
pre-installed, published to Docker Hub as a pull-and-run coding sandbox.

- **End-user usage** (pull, run, git auth, lifecycle) lives in
  [`../DOCKER.md`](../DOCKER.md) and the README's *Docker (Claude Code)* method.
- **This file** is the maintainer reference: how the image is built, versioned,
  and published.

It is a self-contained Gradle module (`io.bitken.ss.docker`) with **no internal
dependencies** — it consumes the *published* plugin from the `bitkentech`
marketplace, it is not part of the plugin build graph.

> An image built from a feature branch still installs the **last published**
> plugin, not your working tree. `buildImage` is not a way to test local plugin
> changes.

## Prerequisites

- A JDK (the repo's Gradle wrapper drives the build; the module cross-compiles to 21).
- **Docker with BuildKit** — for `buildImage` / `buildAndPush` (not needed for
  `resolveVersions` or the unit tests). Docker is not available inside the
  `shipsmooth-claude` container itself; run image builds on a Docker-capable host.

## Tasks

| Task | What it does |
|---|---|
| `./gradlew :docker:resolveVersions` | Print the component versions a build would bake in (claude-code from the npm `stable` dist-tag, shipsmooth from root `plugin.version`, and the compound tag) |
| `./gradlew :docker:buildImage` | Build the image locally, no push. `-Pimage=repo:tag` for a one-off tag |
| `./gradlew :docker:validateLabels` | Check `latest`'s labels against the Docker Hub Overview (`-Plocal=true` to check against the image itself) |
| `./gradlew :docker:buildAndPush` | Build, then push the image tags **and** the repository Overview to Docker Hub |
| `./gradlew :docker:test` | Unit tests for the tooling |
| `docker/smoke.sh` | End-to-end: real `docker build` + `docker run`, label cross-check, `validateLabels --local`. Needs a Docker host |

`buildAndPush` is outward-facing and is **never** run as a side effect of another
task. Before running it:

```sh
export CLAUDE_API_KEY="sk-ant-api..."      # baked in via BuildKit --secret, not into a layer
docker login                                # push the image tags
export DOCKERHUB_USERNAME="..."             # push the Overview (Hub API)
export DOCKERHUB_TOKEN="..."                # a Docker Hub personal access token

./gradlew :docker:buildAndPush
```

## Versions

- **shipsmooth** — from the repo-root `plugin.version` (`gradle.properties`), the
  single source of truth every module uses. Nothing is pinned in `docker/`. The
  release bumps `plugin.version` and publishes the plugin in the same pass, so
  the value baked into the image matches what the marketplace serves.
- **Claude Code** — resolved live from the npm `stable` dist-tag at build time.
  Claude Code self-updates at runtime, so the label records the *starting* version.

### The three version channels

Docker Hub's Tags page shows neither labels nor annotations, so the same
information is repeated in three places, each with a different failure mode:

| Channel | Serves | Drift risk |
|---|---|---|
| OCI labels (`docker inspect`) | scripts, `docker inspect` | none — immutable |
| Repository Overview table | anyone browsing the Hub page | can lag by one build |
| Compound tag name (`claude-<cc>-ss-<ss>`) | someone scanning the Tags list | none |

`buildAndPush` pushes the Overview *before* the image (a failed Overview push then
aborts before anything is published) and runs `validateLabels` afterwards. Every
build is pushed as the immutable compound tag and a dated tag, alongside `latest`.

Read an image's labels with:

```sh
docker inspect --format '{{json .Config.Labels}}' bitkentech/shipsmooth-claude:latest
```

| Label | Meaning |
|---|---|
| `io.bitken.ss.claude-code.version` | `@anthropic-ai/claude-code` version baked in at build |
| `io.bitken.ss.shipsmooth.version` | shipsmooth plugin version (== `plugin.version` at build) |
| `org.opencontainers.image.version` | the compound tag, e.g. `claude-2.1.236-ss-0.3.36` |
| `org.opencontainers.image.revision` | the git commit the image was built from |
