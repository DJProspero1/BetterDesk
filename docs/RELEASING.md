# Local builds and release preparation

Current delivery is a private development build. The source may be prepared for
open source, but repository publication, consumer distribution and public downloads
are separate decisions. The owner has authorized EXE/APK downloads in this
existing private GitHub repository. The public website remains a waitlist.
All native builds, signing and packaging remain local; GitHub only stores source
and the completed assets. No cloud build workflow is used.

## Version and source preparation

Update version references together:

- `android/build.gradle.kts`: `versionName` and monotonically increasing `versionCode`.
- `desktop/build.gradle.kts`: `packageVersion`.
- `scripts/build-local.ps1` and `scripts/package-portable.ps1`: artifact/version defaults.
- `README.md`, this guide and [CHANGELOG.md](../CHANGELOG.md): delivery names/history.

Version 0.7.0 uses Android versionCode 8. There is not yet a single version manifest;
search for the previous version before committing. Keep the Android application ID
and the signing key stable for updates. Update [VERIFICATION.md](VERIFICATION.md)
with actual coverage and limits, not intended tests. Check instruction-file parity
and preserve generated icons if their source changed.

## Build and inspect

For 0.7.0 the owner paused tests and audio workloads. Use `-SkipTests -LowResource`
for authorized local packaging, and clearly record unverified runtime coverage.
Do not launch native apps, devices or tests just to fill the ordinary checklist
while that instruction remains active. Release notes must distinguish compilation,
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
| `BetterDesk-Windows-0.7.0.exe` | Unsigned x64 portable EXE, including app and Java runtime |
| `BetterDesk-Android-0.7.0-debug.apk` | Local debug-signed Android APK |
| `SHA256SUMS.txt` | SHA-256 of those final artifacts |
| `BUILD-INFO.txt` | UTC build time, source Git revision, dirty state and packaging notes |

```powershell
Get-FileHash .\dist\BetterDesk-Windows-0.7.0.exe -Algorithm SHA256
Get-FileHash .\dist\BetterDesk-Android-0.7.0-debug.apk -Algorithm SHA256
Get-Content .\dist\SHA256SUMS.txt
git rev-parse HEAD
git status --short
```

Record a dirty checkout if applicable; do not pretend the revision alone identifies
uncommitted source. Byte-for-byte reproducible packages are not established:
timestamps, the local signing key, JDK vendor and resolved native artifacts matter.

## Distribution cleanup and private GitHub Releases

Every successful `build-local.ps1` invocation stages four complete outputs under
`desktop/build/distribution-stage-*`, validates checksums, then replaces `dist/`.
Only the current EXE/APK and two metadata files remain in `dist/`. Failed compilation,
packaging or checksum verification preserves the prior working distribution.
Recognized older binaries are removed after replacement; unrelated notes/previews
are preserved under `desktop/build/distribution-previous-*`. Links/junctions in
the distribution tree are rejected. Do not point cleanup at arbitrary paths.

For each delivery, finish tests/docs, commit the source, build locally from that
clean commit, verify the final binaries, then push that commit to `origin/main`
under the owner's authorization. Prepare release notes describing changes and
actual test limits (never codes, private screenshots or credentials). With the
authorized local GitHub CLI login:

```powershell
.\scripts\test-release-scripts.ps1
.\scripts\publish-release.ps1 -Version 0.7.0 -NotesFile .\desktop\build\release-notes.md
```

The publisher checks repository privacy, instruction parity, clean source,
`origin/main`, the build's source revision and artifact hashes. It creates/reuses a
draft, uploads all four files, downloads them again and compares every SHA-256.
Draft retries update the intended source commit and notes. Draft validation uses
the authenticated release listing and release ID; GitHub's by-tag lookup does not
resolve an unpublished draft. Publication is then read back by that same ID.
Only then does it publish the new latest release and delete older versioned app
releases/assets. Git source tags/history and unrelated drafts are retained.
Never remove the last working release before verifying its replacement. A failed
upload/readback leaves the earlier release intact. Published versions cannot be
overwritten: increment the version and rebuild. Final GitHub readback must show
the new release and its four uploaded assets. This remains a private, unsigned
Windows/debug-signed Android development channel, despite GitHub's Latest label.

The offline script tests use an isolated checkout and Git/GitHub doubles. They
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
Reinstall/update requires a user/authorized-device action; silent consumer updates
are not implemented. Test-only instrumentation and motion-fixture APKs stay separate.

## Before public distribution

These remain release work, not checks already completed:

- Explicit authorization to make source or binaries public; private source history
  review for secrets, personal data and accidental test artifacts.
- Independent protocol/platform security review, abuse response and a working
  private vulnerability-reporting channel.
- Signed Windows and Android release artifacts, protected production keys and a
  documented update trust/rollback policy. The current debug/unsigned builds are
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
