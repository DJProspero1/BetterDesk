# Local development

## Supported development environment

The verified build host is Windows 11 x64. Android's minimum declared API is 26
(Android 8); compile/target SDK is 36. The tested Android environment is an API 36
emulator, not a certification of every supported API or vendor. Windows native
capture, FFmpeg binaries and portable packaging currently target x64 Windows.
Linux/macOS desktop support and Windows ARM64 packaging are not implemented.

For direct-connection work, the owner authorized local Linux compilation and
synthetic testing as a workaround on 10 October 2026. With JDK 17, Android SDK 36,
Node 24 and Xvfb, both application source sets can compile locally and shared
tests can run without real capture. See [the experimental proof](../experimental/README.md)
for bounded commands and opt-in dependencies. This does not verify Windows native APIs or make Linux a supported product target.
For unchanged Windows dependencies/runtime only, the [release guide](RELEASING.md#local-linux-packaging-with-a-verified-windows-baseline) describes local cross-packaging from a verified previous release.

Install locally:

| Tool | Used for |
| --- | --- |
| Git and PowerShell | Checkout and local scripts |
| Full JDK 17 with `jlink`/`jpackage` | Kotlin compilation and bundled Windows runtime |
| Android SDK platform 36, build tools and platform-tools | Android compilation and ADB |
| Windows .NET Framework 4.x compiler | Single-file portable EXE wrapper |
| Node.js 24 and the infrastructure checkout | Real broker integration-test fixture |

Set `JAVA_HOME` to your JDK 17 installation and ensure `java`, `git` and `node`
resolve in this shell. Accept Android SDK licenses using the SDK's own tools.
The wrapper pins Gradle 8.14.3 and its distribution SHA-256. Kotlin 2.1.20,
Compose 1.8.2 and Android Gradle Plugin 8.11.0 are declared in
[build.gradle.kts](../build.gradle.kts). Dependencies download on first use;
building uploads no source or native build work.

## First checkout

Clone the [application repository](https://github.com/DJProspero1/BetterDesk) with
authorized access. Create ignored `local.properties` at its root:

```properties
sdk.dir=C:/path/to/Android/Sdk
```

Enable the instruction-parity hook as described in
[CONTRIBUTING.md](../CONTRIBUTING.md). For the complete ordinary test suite, clone
[BetterDesk-Infrastructure](https://github.com/DJProspero1/BetterDesk-Infrastructure)
as a sibling directory and run `npm ci` there with Node 24. It is a separate
private repository; access to this app does not automatically grant access to it.
`npm ci` installs fixture dependencies; it does not start a server.

```text
workspace/
  BetterDesk/
  BetterDesk-Infrastructure/
    scripts/broker-fixture.mjs
```

The app build script finds that fixture automatically. For a different layout,
set `BETTERDESK_BROKER_FIXTURE` to its absolute file path. Without it, the nine
broker integration checks skip; that is reduced coverage, not a full pass. The
fixture opens temporary loopback listeners and exits when the test closes stdin.
No Railway credentials or production database are required for those checks.

## Build both apps

From the app repository in PowerShell:

```powershell
.\scripts\build-local.ps1
```

This checks agent-file parity, creates/verifies an ASCII-path junction, runs the
desktop suite, builds both native packages, wraps Windows as a portable EXE, and
writes checksums/build metadata under ignored `dist/`. Outputs are staged and
verified before replacing the previous distribution. After success, `dist/`
contains only the current EXE, APK and three metadata files (including `update.json`). Failed builds preserve
the previous distribution; unrelated previews/notes are preserved outside `dist/`.
Publishing to GitHub Releases is a separate explicit local script. See
[RELEASING.md](RELEASING.md) for artifact names and signing limitations.

JDK packaging can fail under non-ASCII paths. By default the checked junction is
`%LOCALAPPDATA%\BetterDesk\workspace`, pointing to this repository. Use
`-WorkRoot C:\BetterDeskBuild` if your Windows profile path also contains non-ASCII
characters. The script refuses to reuse a junction pointing to another checkout;
choose a different WorkRoot rather than deleting or repointing unrelated paths.
Source files remain in the checkout. No server is launched.

The script disables Kotlin incremental compilation to avoid stale-cache behavior
observed in this environment. `-SkipTests` is available for packaging after the
relevant tests have already passed; never describe that invocation as a test run.

When the owner explicitly suspends tests, honor that suspension and record the
missing coverage. `./scripts/build-local.ps1 -SkipTests -LowResource` compiles and
packages with one worker, a 1536 MiB Gradle heap and the Kotlin compiler in-process.
It does not launch either app, an emulator, audio fixtures or tests. BUILD-INFO.txt
records skipped tests. Local artifact creation does not establish runtime behavior.

After the initial build, individual tasks can use the same junction:

```powershell
$workspace = Join-Path $env:LOCALAPPDATA 'BetterDesk\workspace'
& "$workspace\gradlew.bat" -p $workspace :android:assembleDebug --no-daemon '-Pkotlin.incremental=false'
```

If using a custom WorkRoot, adjust `$workspace`. Check `$LASTEXITCODE` after native
commands. A Gradle success with skipped tests does not prove those paths work.

## Source map

| Path | Responsibility |
| --- | --- |
| `shared/src/main/kotlin/app/betterdesk/ui/` | Shared Compose screens, copy, flags and icon |
| `shared/src/main/kotlin/app/betterdesk/core/` | Session state, pairing, TLS, relay, wire, queue/recovery rules |
| `android/src/main/` | Activity, preferences, capture, encoder, accessibility, services/resources |
| `desktop/src/main/` | Window host, preferences, DXGI/GDI capture, native input and AVC decoder |
| `desktop/launcher/Portable.cs` | Single portable EXE wrapper and runtime extraction |
| `desktop/src/test/` | Shared/JVM, rendering, broker and opt-in device/benchmark checks |
| `android/src/androidTest/` | Android instrumentation and UI Automator checks |
| `motion-fixture/` | Separate synthetic Android animation/codec test app |
| `assets/` | Canonical icon geometry and generated PNG/SVG/ICO |
| `scripts/` | Local build, packaging, icons and agent-file parity |

`shared` is a source directory compiled into both Gradle application modules;
it is **not** a separately published module. Do not invent a `:shared:test` task.
`motion-fixture` is never bundled in BetterDesk.

Regenerate icons on Windows with `.\scripts\generate-icons.ps1`; commit the JSON
and every generated variant together. It uses local System.Drawing and generates
the Compose vector, Android legacy/adaptive/monochrome icons and Windows ICO sizes.

## Service configuration and separate projects

The app currently uses `https://api.betterdesk.dev`. Production routing is validated
against Railway TCP hostnames. There is no runtime custom-server setting or
environment-variable override in the shipped app. Self-hosting would require
explicit source changes to the trust policy plus tests and deployment work; do not
remove validation or claim arbitrary relay compatibility.

The infrastructure repository owns the Railway broker, encrypted-byte relay and
waitlist PostgreSQL database. The separate
[BetterDesk-Web repository](https://github.com/DJProspero1/BetterDesk-Web) owns the
Vercel website at `betterdesk.dev`. Their deployment/runbooks belong in those
repositories. No cloud credentials, deployment IDs, database URLs or web build
secrets are needed to compile the native apps. Updating app docs does not require
deploying either service.
