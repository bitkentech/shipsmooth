# Running shipsmooth in Docker (Claude Code)

A quick way to try shipsmooth without touching your host setup: the
`bitkentech/shipsmooth-claude` image is Ubuntu + Node + [Claude Code](https://www.anthropic.com/claude-code)
with the shipsmooth plugin already installed. Pull it, run it against the
directory you want Claude to work in, and start coding — your edits land on the
host.

This is the full guide. For the short version, see the **Docker (Claude Code)**
entry in the [README Installation section](README.md#installation).

## 1. Pull the image

```sh
docker pull bitkentech/shipsmooth-claude:latest
```

## 2. Start the container

From the directory you want Claude to work in:

```sh
docker run -dit \
  --name cc \
  -v "$(pwd):/workspace" \
  -v cc-config:/root/.claude \
  bitkentech/shipsmooth-claude:latest
```

| Flag | Why |
|---|---|
| `-dit` | Detached, with a TTY held open so the container stays alive between `docker exec`s. Drop `-d` for a session that ends when you exit; keep `-t` or bash exits immediately. |
| `--name cc` | Address the container by name, not by generated ID. |
| `-v "$(pwd):/workspace"` | The container works on your code in place; edits land on the host. |
| `-v cc-config:/root/.claude` | A named volume for Claude's config and credentials. Docker seeds it from the image on first run (so the shipsmooth plugin is already there), then it persists independently — your login survives `docker rm`. |

## 3. Enter the container

```sh
docker exec -it cc bash
```

## 4. Set up git access

Inside the container, use GitHub's `gh` command to log in. Choose **HTTPS** when
prompted — that lets you push as well as pull.

```sh
gh auth login
```

Set your commit identity:

```sh
git config --global user.name  "Your Name"
git config --global user.email "you@example.com"
```

## 5. Launch Claude

```sh
claude
```

On first run, `claude` walks you through its login. The container is headless,
so it prints a URL — open it in your browser, approve, and paste the code back.
The login lands in the `cc-config` volume and survives restarts **and**
`docker rm`. No credentials are ever baked into the image itself.

Then start the workflow:

```
/shipsmooth:start
```

On this first run a `SessionStart` hook downloads the self-contained shipsmooth
runtime into `/root/.cache/shipsmooth/` inside the container (needs network) —
the same step described under [What gets installed](README.md#what-gets-installed).

## 6. Detach, stop, resume

Detach from the container with `Ctrl-P Ctrl-Q` to leave it running. If you
started it with `-d`, you can just close the terminal.

```sh
docker stop cc      # pause
docker start cc     # resume; volume and creds intact
docker rm -f cc     # remove (the cc-config volume is NOT deleted)
```

## 7. Update to a new image version

```sh
docker pull bitkentech/shipsmooth-claude:latest
docker rm -f cc
# re-run step 2
```

Your code and the `cc-config` volume are untouched, so you won't log in again.
One caveat: the config volume keeps the shipsmooth plugin from the image it was
*first created* against. To pick up a newer bundled plugin, drop the volume and
re-authenticate:

```sh
docker volume rm cc-config    # next run starts clean, needs a fresh login
```

## Running on a remote box

The steps are essentially identical on a remote Linux box: `ssh` in, make sure
Docker is installed, then follow the steps above.

## Troubleshooting

**Container exits immediately.** You dropped the `-t` from `-dit`. Without a
TTY, bash gets no stdin and exits. (Dropping just `-d` is fine — `-it` alone
works.)

**No network inside the container** (`apt-get update` hangs, git can't reach
GitHub). Test the basics first:

```sh
docker run -it --rm ubuntu:26.04 apt-get update
```

**Permission errors on files the container made.** It runs as root, so those
files are root-owned on the host. Fix with:

```sh
sudo chown -R "$(id -u):$(id -g)" .
```

**On an Apple Silicon Mac:** the image is currently `linux/amd64` only, so it
runs under emulation and is slower.

## Which version am I running?

Component versions are recorded as OCI labels in the image:

```sh
docker inspect --format '{{json .Config.Labels}}' bitkentech/shipsmooth-claude:latest
```

| Label | Meaning |
|---|---|
| `io.bitken.ss.claude-code.version` | `@anthropic-ai/claude-code` version baked in at build |
| `io.bitken.ss.shipsmooth.version` | shipsmooth plugin version |
| `org.opencontainers.image.version` | the compound tag, e.g. `claude-2.1.236-ss-0.3.36` |
| `org.opencontainers.image.revision` | the git commit the image was built from |

Claude Code self-updates at runtime, so the label is the *starting* version.
Every build is also pushed as an immutable
`claude-<claude-version>-ss-<shipsmooth-version>` tag and a dated tag, alongside
`latest`.
