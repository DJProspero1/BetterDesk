# Contributing to BetterDesk

BetterDesk is an early attended remote-access application for Windows and Android.
Its source currently lives in a private repository. These instructions prepare
for outside contributors; they do not announce public availability or downloads.
BetterDesk's own code is under [MIT](LICENSE); bundled dependencies retain their
own [licenses](THIRD_PARTY.md).

## Start here

1. Read [project rules](AGENTS.md), [product intent](PRODUCT.md) and
   [design](DESIGN.md). A change normally applies to both applications.
2. Follow [local development](docs/DEVELOPMENT.md) for the toolchain and repository map.
3. Read [architecture](docs/ARCHITECTURE.md) and [platform behavior](docs/PLATFORMS.md).
   Transport changes also require the [protocol](docs/PROTOCOL.md) and
   [security boundaries](SECURITY.md).
4. Use [testing](docs/TESTING.md) for reproducible commands, and
   [releasing](docs/RELEASING.md) when delivering a local build.

## Making a change

Keep changes focused on an observable user problem. Shared UI, wording, settings
and session behavior belong in `shared/src/main/kotlin`; native APIs belong behind
`RemotePlatform`. Explain any necessary difference between Windows and Android.
Never change consent, crypto, host validation or stale-input safeguards merely to
reduce setup steps or make a test pass.

Use a working branch for contributions. Do not include local SDK paths, device
identifiers, access tokens, signing keys, session codes or private screenshots in
a commit or issue. All builds and tests run locally; this project does not use
cloud native-app builds or GitHub Actions. Test fixtures may bind temporary
loopback ports but must close them. Do not start a development server implicitly.

Update documentation alongside behavior. New dependencies need a reason, exact
version, license/notice review, and attention to the final packaged artifacts.
Generated connection icons are committed so a fresh checkout builds without
regeneration; edit their canonical JSON and regenerate all variants together.

## Identical agent instructions

`AGENTS.md`, `CLAUDE.md` and `.claude.md` contain the same project rules.
`AGENTS.md` is canonical for editing, not more authoritative than its mirrors.
`CLAUDE.md` supports the conventional Claude filename; the dotted filename is
also retained as requested. On Windows, `AGENTS.md` is case-insensitively accessible
as `agents.md`; use the tracked uppercase filename for Linux compatibility.

```powershell
# Run after editing AGENTS.md.
.\scripts\check-agent-instructions.ps1 -Sync
.\scripts\check-agent-instructions.ps1

# Once per clone, after checking whether a custom hook already exists.
git config --get core.hooksPath
git config core.hooksPath .githooks
```

The local build script checks byte equality. The tracked pre-commit hook compares
Git's **staged** file blobs, so staging an old mirror also fails. Neither check
silently rewrites or stages instructions. `-Sync` explicitly overwrites the two
mirrors from `AGENTS.md`. Git does not enable repository hooks automatically on
clone; contributors must configure the hook. Hooks can be bypassed, so reviewers
must still check parity. LF attributes keep the three files consistent across OSes.

## Review and evidence

A proposed change should state the problem, resulting behavior, platform coverage,
checks run and limitations. Link the relevant source/docs rather than requiring
reviewers to reconstruct a chat. For a visual change, inspect both sizes and the
native host behavior. For transport/lifecycle changes, verify approval, cancellation,
disconnect and failure cleanup, not just a successful connection.

Before committing, run the appropriate checks in [TESTING.md](docs/TESTING.md),
instruction parity and `git diff --check`. Keep evidence in ignored local
`build/`/`dist/` directories. A passing emulator test is not physical-device
coverage; a static screen is not a sustained FPS benchmark. Record evidence in
[VERIFICATION.md](docs/VERIFICATION.md) with date and scope.

Bug reports should include app version on both ends, OS/API versions, connection
direction, LAN/relay route if known, reproducible steps, expected/actual behavior,
and whether the problem persists with synthetic content. Omit codes, names and
screen/input contents. Security-sensitive findings follow [SECURITY.md](SECURITY.md)
instead of an ordinary issue. Publish verified locally built app binaries only
through the private [release workflow](docs/RELEASING.md). Do not make the repository
public or deploy the separate website/infrastructure as part of a routine app change.
