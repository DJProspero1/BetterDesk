# Testing

The owner resumed tests, app launches and audio checks for direct-connection work
on 10 October 2026. The earlier pause covered 0.7 and 0.7.1; those releases' missing
runtime coverage remains unverified until new checks cover it. The 0.6 suite and
measurements below are historical, not evidence
for native Explorer paste, Android sharing, keyboard control or protocol 4.
The pending keyboard/device matrix is documented in [KEYBOARD.md](KEYBOARD.md).

For the direct-connection PR, the owner has explicitly declined providing a
Windows host. Use local Linux and Android checks and continue Windows source work
with the available toolchain. Do not request Windows access or evaluation-media
registration. Native Windows runtime/device coverage stays explicitly untested
and does not gate implementation or merging this PR. Linux JNI and compatibility
fixtures remain Linux evidence. This does not waive implementation, approval,
cryptography or cleanup requirements.

Run all checks locally. Use synthetic content and dedicated test devices. Native
capture/input tests and UI tests can open windows or interact with a device;
coordinate them with the person using it. Never capture unrelated private work
for a screenshot. No test may bypass genuine OS consent.

The [experimental WebRTC proof](../experimental/README.md) describes the explicit
Gradle opt-in and bounded Linux compilation/synthetic-test workaround. Ordinary
builds do not include its sources or dependencies. Linux local tests select Linux
FFmpeg/JavaCPP test-runtime classifiers; Windows application dependencies retain
their Windows classifiers. Passing that proof cannot establish physical-device,
Windows capture or separate-network coverage. Dedicated Android native
instrumentation/interoperation results are recorded separately with exact APK hashes.

## Ordinary suite

Set up [DEVELOPMENT.md](DEVELOPMENT.md), including the Node broker fixture, then:

```powershell
.\scripts\build-local.ps1
```

For tests alone, use the checked ASCII junction and set the fixture explicitly
(the wrapper script sets it only inside its own process):

```powershell
$workspace = Join-Path $env:LOCALAPPDATA 'BetterDesk\workspace'
$env:BETTERDESK_BROKER_FIXTURE = (Resolve-Path '..\BetterDesk-Infrastructure\scripts\broker-fixture.mjs').Path
& "$workspace\gradlew.bat" -p $workspace :desktop:test --no-daemon '-Pkotlin.incremental=false'
```

The current ordinary suite contains 74 tests: 68 enabled on Windows with the
fixture, and six opt-in device/benchmark tests. On Linux the Windows platform
decoder integration also skips; the portable native decoder check still runs.
Without the fixture another nine checks skip. The WebRTC opt-in adds thirteen
native proof checks, four control-framing checks and three dedicated-device host
checks (data-only, synthetic video and synthetic audio/video).
Audio instrumentation requires microphone permission
denied before and after external PCM exchange; it does not capture device sound.
Control checks cover exact-size Unicode reassembly, replay/generation/order and
length rejection, malformed UTF-8/schema, incomplete-message timeout and Stop.
Native Linux, Android-only data and all three interoperation profiles exchange
the same finite ordered control fixture without executing its input/clipboard
messages. Linux also checks that native control replay closes its receiving peer.
Coverage includes invitation bounds, J-PAKE, pinning, approval before capture,
input validation, cancellation, remote Stop racing queued input/control writes,
route fallback, frame recovery, AVC decoding and
shared UI. Switch/audio checks cover approval, decline, cancellation, repeated
reversal, stale callbacks, mute, denied new capture, Android navigation policy and
PCM conversion. No ordinary test captures an unrelated real desktop.

`UpdateTest` serves a bounded loopback release fixture (never GitHub) and checks
version comparison, origin pinning, manifest validation, size/SHA-256 rejection
with partial-file cleanup, redirect restriction, consent before download, no
installation during a connection, Android permission/versionCode gating and Later.
The Windows file replacement/relaunch and Android `PackageInstaller` paths are
not covered by automated tests. The origin is pinned, so verify them end to end
with the first real release that follows an updater-enabled one: install the older
build, publish, choose Update, and confirm the new version opens with its language
preference kept.

Inspect `desktop/build/reports/tests/test/index.html` and XML under
`desktop/build/test-results/test/`; report failures and skipped checks separately.
UI renders appear in `desktop/build/screenshots/`. Subsequent filtered test runs
can overwrite Gradle reports: preserve a dated local summary before running them.
Historical evidence and limits belong in [VERIFICATION.md](VERIFICATION.md).

## Android setup and local checks

Use an already-running dedicated emulator or connected test phone. These commands
do not start an emulator. Select its serial from `adb devices`; do not select a
random attached device. The host device tests currently look for ADB at
`%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`. A nonstandard SDK path needs
a corresponding test-harness change; `local.properties` alone does not override it.

```powershell
$workspace = Join-Path $env:LOCALAPPDATA 'BetterDesk\workspace'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$serial = 'emulator-5554' # Replace with the intended test device.
& $adb devices
& "$workspace\gradlew.bat" -p $workspace :android:assembleDebug :android:assembleDebugAndroidTest :motion-fixture:assembleDebug --no-daemon '-Pkotlin.incremental=false'
& $adb -s $serial install -r .\android\build\outputs\apk\debug\android-debug.apk
& $adb -s $serial install -r .\android\build\outputs\apk\androidTest\debug\android-debug-androidTest.apk
& $adb -s $serial install -r .\motion-fixture\build\outputs\apk\debug\motion-fixture-debug.apk
& $adb -s $serial shell am instrument -w -e class app.betterdesk.LanguagePreferenceTest app.betterdesk.test/androidx.test.runner.AndroidJUnitRunner
& $adb -s $serial shell am instrument -w -e class app.betterdesk.LocalRouteTest app.betterdesk.test/androidx.test.runner.AndroidJUnitRunner
```

Stop on a failed build/install. An ADB process can exit zero even when
instrumentation fails: require an `OK (...)` result and inspect the test output.
LanguagePreferenceTest uses the real flag and verifies activity recreation and a
new platform/session instance. It is not a physical process-death test.
LocalRouteTest exercises Android discovery and a complete direct session with
synthetic endpoints on one device, not a physical two-device Wi-Fi connection.

## Live Railway integration (explicit opt-in)

These tests use the public service from a local process. They allocate short-lived
rooms and clean them up; they need no administrator keys. Use a temporary shell,
or restore prior environment values afterward so an ordinary suite cannot
accidentally contact production.

```powershell
$env:BETTERDESK_RELAY_LIVE = '1'
& "$workspace\gradlew.bat" -p $workspace :desktop:test --rerun --tests app.betterdesk.RelayIntegrationTest --no-daemon '-Pkotlin.incremental=false'
Remove-Item Env:BETTERDESK_RELAY_LIVE

# After installing all three APKs above:
$env:BETTERDESK_ANDROID_RELAY = '1'
$env:BETTERDESK_ANDROID_SERIAL = $serial
& "$workspace\gradlew.bat" -p $workspace :desktop:test --rerun --tests app.betterdesk.AndroidRelayDeviceTest --no-daemon '-Pkotlin.incremental=false'
Remove-Item Env:BETTERDESK_ANDROID_RELAY
Remove-Item Env:BETTERDESK_ANDROID_SERIAL
```

The device harness invokes RelayDeviceTest with private temporary test parameters;
do not run that instrumentation class without the host harness. The capture
direction measures actual Android capture/AVC and Windows decoding over Railway,
and native playback sound, then checks notification/capture cleanup. The viewer direction uses synthetic
Windows frames and an input sink, exercises Android rotation/full-screen controls,
real keyboard text sending, actual AudioTrack playback, switches requested by both
devices and fresh Android capture consent, then disconnect. It does not operate the real Windows OS.

Use `--rerun` for host device tests: the installed test APK is an external input,
so Gradle can otherwise report UP-TO-DATE after that APK changes. Reports include
`android-relay-share.txt`, `android-relay-view.txt` and, for capture measurement,
`android-relay-performance.txt` under `desktop/build/reports/`.

## Performance and manual acceptance

The six opt-in checks are AndroidDeviceTest, two AndroidRelayDeviceTest checks,
CaptureBenchmarkTest, StreamBenchmarkTest and NativeAudioTest. The latter plays
a brief quiet synthetic tone and verifies native WASAPI loopback and restart:

```powershell
$env:BETTERDESK_NATIVE_AUDIO = '1'
& "$workspace\gradlew.bat" -p $workspace :desktop:test --rerun --tests app.betterdesk.NativeAudioTest --no-daemon '-Pkotlin.incremental=false'
Remove-Item Env:BETTERDESK_NATIVE_AUDIO
.\scripts\test-release-scripts.ps1
```

Follow
[PERFORMANCE.md](PERFORMANCE.md) for their switches and benchmark method. Capture
FPS, decoded FPS, distinct drawn FPS and input-to-photon latency are different
measurements. Never infer a new result from an old release's benchmark.

For relevant UI/lifecycle changes, check both directions and both native apps:

| Area | Check |
| --- | --- |
| Connection | Same code/Connect flow; approval, decline, cancellation, timeout and retry |
| Network | Reachable LAN preferred; blocked/unavailable LAN falls back before approval; interruption stops safely |
| Viewer | No persistent header; portrait/landscape, window resize, insets, full-screen exit/re-entry, no image crop |
| Input | View-only disables input; overlay preserves image bounds; text remains reachable with keyboard open |
| Switch screens | Either endpoint requests; the other approves/declines; pending input disabled; fresh Android capture consent; no new code; old capture stops |
| Sound | Native playback both directions; viewer mute/unmute; denied/unsupported capture keeps video; stop/switch releases audio; no microphone |
| Language | Change flag; switch apps; rotate; close/reopen; update same signed build; choice remains |
| Android lifecycle | Genuine capture prompt; restricted-settings instructions; notification during sharing and removal after either end stops |
| Windows identity | Outer EXE, extracted EXE, open window and taskbar show the same icon; test existing pins separately |
| Cleanup | No continuing capture, active input, obsolete notifications or orphan fixture listeners after stop/error |

Test physical phones, vendor permission screens/keyboards, high-DPI Windows and
constrained networks before making compatibility claims. Record untested rows as
pending, not passed. See [PLATFORMS.md](PLATFORMS.md) for expected limitations.

## Remembered devices (0.9.0)

`RememberedDevicesTest` checks canonical signature binding of every invitation
field, signed LAN/relay parsing, expiry, wrong keys, legacy invitations and corrupt,
duplicate/oversized grant stores. `SessionTest` checks the local-before-capture
choice, first approval, persistence across session recreation, saved reconnect,
removal, Just this time, cancellation, failed storage, copied-link rejection and
Android view-only behavior without an explicit device grant. `RelayIntegrationTest`
adds first/save/reconnect and a different key with the same name through the real
bounded loopback broker. `UiTest` renders the prompt at phone/desktop sizes in
Portuguese/English and confirms it is absent on the waiting controller.

The opt-in Railway device fixtures explicitly choose Just this time when a real
platform signs a request, keeping fixture runs from saving persistent grants.

Native acceptance still needs actual AndroidKeyStore persistence across updates,
MediaProjection consent with a remembered peer, accessibility revocation,
app-only versus full-display capture, Windows DPAPI persistence, lock/protected
screens and both physical directions. The local Linux suite cannot establish
those platform results. The Linux packaging helper compares retained Windows
payload bytes and reads back the outer EXE; this is artifact verification, not
execution of Windows APIs.
