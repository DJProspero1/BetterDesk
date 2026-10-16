# Local builds and release preparation

Current delivery is a development build. On 12 October 2026 the owner authorized
making this app repository and its Releases public, so installed apps can update
without credentials. The website and infrastructure repositories stay private.
The public website remains a waitlist.
All native builds, signing and packaging remain local; GitHub only stores source
and the completed assets. No cloud build workflow is used.

## Version and source preparation

`gradle.properties` holds the only version: `betterdesk.version` (X.Y.Z) and the
monotonically increasing Android `betterdesk.versionCode`. Both Gradle apps and
`build-local.ps1` read them. Also update `README.md`, this guide and
[CHANGELOG.md](../CHANGELOG.md). Version 0.9.0 uses Android versionCode 12.
Every release must raise both: the updater offers only a higher X.Y.Z, and Android
additionally needs a higher versionCode. Keep the Android application ID
and the signing key stable for updates. Update [VERIFICATION.md](VERIFICATION.md)
with actual coverage and limits, not intended tests. Check instruction-file parity
and preserve generated icons if their source changed.

## Build and inspect

Use `-SkipTests -LowResource` only when the owner has paused tests, and clearly
record unverified runtime coverage. Release notes must distinguish compilation,
artifact checksum readback and actual end-to-end verification.

1. Follow [DEVELOPMENT.md](DEVELOPMENT.md), including the real broker fixture.
2. Run `.\scripts\build-local.ps1`. It tests, compiles and packages locally.
3. Run relevant native/transport checks from [TESTING.md](TESTING.md). A new APK
   requires fresh instrumentation; an UP-TO-DATE host test is insufficient.
4. Inspect the actual portable EXE and APK. Confirm both versions, icons, language
   persistence and the affected real flow. Check capture/notification cleanup.
5. Verify `dist/SHA256SUMS.txt` against the final files; preserve local build metadata
   and source revision with the verification report.

Local outputs for this milestone:

| File under `dist/` | Meaning |
| --- | --- |
| `BetterDesk-Windows-0.9.0.exe` | Unsigned x64 portable EXE, including app and Java runtime |
| `BetterDesk-Android-0.9.0-debug.apk` | Local debug-signed Android APK |
| `SHA256SUMS.txt` | SHA-256 of those final artifacts |
| `BUILD-INFO.txt` | UTC build time, source Git revision, dirty state and packaging notes |
| `update.json` | Version, Android versionCode, and each artifact's name, size and SHA-256 for in-app updates |

```powershell
Get-FileHash .\dist\BetterDesk-Windows-0.9.0.exe -Algorithm SHA256
Get-FileHash .\dist\BetterDesk-Android-0.9.0-debug.apk -Algorithm SHA256
Get-Content .\dist\SHA256SUMS.txt
git rev-parse HEAD
git status --short
```

Record a dirty checkout if applicable; do not pretend the revision alone identifies
uncommitted source. Byte-for-byte reproducible packages are not established:
timestamps, the local signing key, JDK vendor and resolved native artifacts matter.

## Distribution cleanup and GitHub Releases

Every successful `build-local.ps1` invocation stages five complete outputs under
`desktop/build/distribution-stage-*`, validates checksums, then replaces `dist/`.
Only the current EXE/APK and three metadata files remain in `dist/`. Staging also
checks that `update.json` matches both artifacts byte-for-byte (size and SHA-256). Failed compilation,
packaging or checksum verification preserves the prior working distribution.
Recognized older binaries are removed after replacement; unrelated notes/previews
are preserved under `desktop/build/distribution-previous-*`. Links/junctions in
the distribution tree are rejected. Do not point cleanup at arbitrary paths.

For each delivery, finish tests/docs, commit the source, build locally from that
clean commit, verify the final binaries, then merge its reviewed PR under the owner's authorization. VMs push only task
branches; refresh the primary checkout and build from the clean merged revision. Prepare release notes describing changes and
actual test limits (never codes, private screenshots or credentials). With the
authorized local GitHub CLI login:

```powershell
.\scripts\test-release-scripts.ps1
.\scripts\publish-release.ps1 -Version 0.9.0 -NotesFile .\desktop\build\release-notes.md
```

The publisher checks that the repository is public (installed apps fetch
anonymously), instruction parity, clean source,
`origin/main`, the build's source revision and artifact hashes. It creates/reuses a
draft, uploads all five files, downloads them again and compares every SHA-256.
Draft retries update the intended source commit and notes. Draft validation uses
the authenticated release listing and release ID; GitHub's by-tag lookup does not
resolve an unpublished draft. Publication is then read back by that same ID.
Only then does it publish the new latest release and delete older versioned app
releases/assets. Git source tags/history and unrelated drafts are retained.
Never remove the last working release before verifying its replacement. A failed
upload/readback leaves the earlier release intact. Published versions cannot be
overwritten: increment the version and rebuild. Final GitHub readback must show
the new release and its five uploaded assets. Publishing it as Latest is what makes
installed apps offer it, so publish only verified builds. This remains an unsigned
Windows/debug-signed Android development channel, despite GitHub's Latest label.

## In-app updates

Both apps share `AppUpdater` and `UpdateSource` (`shared/.../core/AppUpdate.kt`);
`DesktopUpdate` and `AndroidUpdate` install. The design follows open-source desktop
apps such as T3 Code (electron-updater with public GitHub Releases and a hashed
`latest.yml`), adapted to local builds without GitHub Actions:

1. About 15 seconds after opening, then every six hours, the app anonymously
   fetches `https://github.com/DJProspero1/BetterDesk/releases/latest/download/update.json`
   (at most 16 KiB). Failures are silent; the next check retries.
2. A higher X.Y.Z (and, on Android, a higher versionCode) shows a home-screen
   notice. Later hides that version until the next one. The notice never appears
   on connection screens.
3. Update downloads the platform asset from the same release. Redirects may only
   go to `github.com` or `*.githubusercontent.com` over HTTPS. The file is written to
   a `.part` file and kept only when its size and SHA-256 equal the manifest.
4. Installing waits until no connection is pending or active (otherwise the notice
   says the update is ready and offers Install update).
   - Windows: the launcher passes its own path in `BETTERDESK_PORTABLE`. The app
     downloads beside it (`<exe>.new`), renames the running EXE to `<exe>.old`,
     moves the new file into place, starts it with `--after <pid>` and exits. The
     new launcher waits for that process, deletes `<exe>.old`, and removes completed
     caches older than ten minutes that no running copy holds open. If the EXE's
     folder is not writable, the update fails visibly and the app is unchanged.
   - Android: after Android's per-app **Install unknown apps** permission (explained
     in the notice, rechecked on return), the APK is checked for the same package,
     a higher versionCode and identical signing certificates, then committed through
     `PackageInstaller`, which always shows Android's confirmation.

Trust model and limits: authenticity rests on HTTPS to GitHub and on control of
this GitHub account; the manifest is not separately signed. Anyone who can publish
a release here can offer Windows users an arbitrary EXE (still requiring their
Update click). Android is stronger: a different signing key is refused. Before
public distribution, add Windows code signing (and verify it before replacing the
EXE) or a detached manifest signature with a key held offline. Versions before
0.8.0 contain no updater.

Distribution path guards use native path separators and case rules; the same
PowerShell script suite runs on Windows and Linux (a junction or symlink is rejected
respectively). The offline script tests use an isolated checkout and Git/GitHub doubles. They
exercise failed checksums, safe cleanup, junction rejection, upload failure and
publication-before-deletion without modifying GitHub.

## Windows packaging

Compose `:desktop:createDistributable` produces an app image under
`desktop/build/compose/binaries/main/app/BetterDesk/`. The package script embeds a
ZIP payload in a .NET Framework WinExe compiled for x64 with the generated ICO.
The user opens one EXE; they never manually extract a ZIP or install Java.

On first opening a build, the wrapper extracts to
`%LOCALAPPDATA%/BetterDesk/portable/<content-hash>/` and launches the inner EXE.
Later launches reuse that cache. The outer launcher, inner executable and native
window each need the correct icon; inspecting only `assets/betterdesk.ico` is not
a taskbar test. No service, auto-start entry or installer is configured. Previous
caches may be removed manually only after their processes are closed; never delete
an unverified computed path. Language preferences are stored separately per user.

The bundled runtime must include HTTPS/TLS providers, Java Preferences and all
modules declared in the desktop build. Verify the packaged runtime against HTTPS,
relay registration and ephemeral TLS after dependency/runtime changes, not just
the developer's installed Java. Preserve its upstream legal notices.

## Android updates and signing

The development APK is signed with the local Android debug key (normally
`%USERPROFILE%/.android/debug.keystore`). The key is not a repository file.
Another developer's debug key is a different signing identity and cannot update
the same installed app. Protect/back up keys through an approved local mechanism;
never upload them or put passwords in commands, commits or reports.

Install an update with Android's genuine confirmation or, on an authorized test
device, `adb -s <serial> install -r <apk>`. Do not automatically uninstall a
signature-conflicting app: that clears preferences and can affect OS grants.
Reinstall/update requires a user/authorized-device action; the in-app updater uses
Android's confirmation and silent updates are not implemented. Every APK published
for in-app updates must be signed with the same key as the installed app, so keep
building releases on the machine that holds the original debug key. Test-only instrumentation and motion-fixture APKs stay separate.

## Before public distribution

These remain release work, not checks already completed:

- Source history was reviewed for secrets on 12 October 2026 before the repository
  was made public (none found; commit author emails are public). Repeat the review
  for later history-rewriting or bulk imports.
- Independent protocol/platform security review, abuse response and a working
  private vulnerability-reporting channel.
- Signed Windows and Android release artifacts, protected production keys, a
  signed update manifest and a rollback policy (the updater cannot downgrade). The current debug/unsigned builds are
  not that release channel.
- Complete resolved dependency/SBOM and notice audit for both final artifacts,
  including native libraries and the embedded JDK. Provide corresponding source
  where required; see [THIRD_PARTY.md](../THIRD_PARTY.md).
- Physical-device/OEM, rotation/keyboard, high-DPI/multi-display and restrictive
  network testing, plus honest performance and input-latency measurements.
- A reviewed privacy/service policy, maintenance/support scope and tested relay
  capacity/recovery. The single Railway replica interrupts sessions on restart.

The public-readiness list does not authorize a deployment. Website and infrastructure
deployment/rollback procedures belong to their separate repositories. Validate
those services only as part of an authorized change to them.

## Local Linux packaging with a verified Windows baseline

The 0.8.1 development release was packaged on Linux with its existing Windows
jpackage launcher/JRE. The same bounded path is available when **desktop build
configuration, dependencies and runtime requirements have not changed**. This
compiles the current JVM application and portable .NET wrapper locally; it reuses
the prior release's Windows native dependencies and JRE, preserving legal notices.
It does not execute the Windows app or validate DPAPI, capture, input or updates.
Runtime/module/dependency changes require a fresh Windows-native package instead.

For the next release, raise the source version above the latest published
version, run the relevant checks, and commit the source. With Python 3, Mono
(`mcs` and `mono`), JDK 17 and SDK 36, download that latest published release as
the older runtime baseline into an ignored build path. Only the latest release's
assets are retained, so do not request a superseded release tag's downloads:

```sh
mkdir -p desktop/build/windows-baseline
gh release download --repo DJProspero1/BetterDesk \
  --pattern '*exe' --pattern update.json --dir desktop/build/windows-baseline
ANDROID_HOME=/path/to/android-sdk \
  python3 scripts/package-windows-from-release.py desktop/build/windows-baseline
```

The helper requires the baseline version to be lower than the new source version.
Keep a locally cached baseline if reproducing an earlier package: for example,
0.9.0 used the 0.8.1 EXE recorded in BUILD-INFO.txt, whose superseded release assets
were removed after verified replacement. Exact package reproducibility is not
established; source tags remain available.

The helper checks the baseline EXE against its published size/SHA-256, compares
build configuration to the baseline source tag, compiles `:desktop:jar`, replaces
only the application JAR and version options, compiles Portable.cs with Mono, and
reads back the final EXE's payload. Every retained dependency/runtime entry is
compared byte for byte. The generated EXE is under `desktop/build/`; it does not
publish, replace `dist/` or remove earlier outputs. Inspect it alongside the fresh
same-key Android APK, stage both with SHA256SUMS.txt, BUILD-INFO.txt and update.json
in `desktop/build/distribution-stage-*`, then use `publish-distribution.ps1` and
the existing release publisher. Record the exact runtime baseline, source revision,
clean-tree state and absent Windows runtime testing in build metadata/release notes.
