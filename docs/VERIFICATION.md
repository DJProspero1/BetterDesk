# Local verification

Current scope, confirmed by the owner on 11 October 2026: no Windows host will be
provided. Continue implementation and merge assessment with local Linux/Android
checks; native Windows runtime/device coverage is an accepted untested gap, not
a prerequisite for this PR. Do not request Windows access or evaluation-media
registration. Earlier dated results retain their original scope; this decision
does not turn Linux/emulator checks into Windows evidence.

## In-app updates, 12 October 2026 (0.8.0 source, unreleased)

On Linux: the full desktop JVM suite ran **65 checks: 49 passed, zero failed,
16 skipped** (no broker fixture on this host, opt-in device/benchmark checks and
the Windows decoder). All nine new `UpdateTest` checks passed against a loopback
release fixture. `:android:assembleDebug` produced versionName 0.8.0, versionCode
10, with `REQUEST_INSTALL_PACKAGES`. The changed Windows launcher type-checked with
Mono `mcs`; the real `csc` build, EXE replacement/relaunch, cache cleanup, Android
`PackageInstaller` confirmation and a real GitHub download are **untested** until
the first release after an updater-enabled one.

## Direct transport implementation, 11 October 2026: bounded control checkpoint

The opt-in proof now carries the existing control-message schema over native
DTLS/SCTP, with bounded canonical fragmentation/reassembly, fixed generation,
consecutive sequences and strict UTF-8/schema decoding. Four admitted writers
use a fair timed lock; reads and whole-message writes have bounded deadlines.
Oversize fields are rejected before encoding. Errors hide serialized content and
preserve interruption. A native callback/Stop race was fixed by locking inbox
enqueue with revocation. This remains an experimental adapter, with no production
capture/input/clipboard/file execution or route cutover.

Final local compilation and both proof APK packages passed. The full Linux JVM
suite ran **76 checks: 66 passed, zero failed, ten skipped** (six existing opt-ins,
Windows decoder integration and three dedicated Android host checks). All thirteen
native proof checks passed in 2.025 seconds and all four control-framing checks
passed in 0.199 seconds. Coverage includes Unicode and exact-size reassembly,
replay/context/order/length rejection, malformed UTF-8/schema, incomplete-message
timeout, interruption and Stop. Native replay closes its receiving peer without
executing the synthetic input.

The final APKs passed **three Android-only native checks in 1.567 seconds** on the
dedicated API 36.1/Android 16 emulator. All three desktop/Android host checks also
passed on those exact APKs, including the framed control fixture in both directions:

- Data-only: 2.654 seconds.
- Synthetic video: 2.525 seconds.
- Synthetic audio/video: 3.730 seconds.

These host-fixture times include installation/setup and are not latency or FPS
benchmarks. The finite control fixture transports ordered Unicode keyboard/text,
clipboard barrier/ack, maximum-sized text and switch/status messages; it never
executes an OS action. The media checks retain synthetic decoded pixels, PCM peaks
and RTP counter evidence. Microphone permission remained denied. Installed APK
hashes matched the build outputs before/after host checks; private fixture
configuration and ADB reverse rules were absent afterwards. The owned emulator
was stopped after verification.

Final control/media proof APK SHA-256 values:

- App: `228cc7e4b8b7d5b9e1b74e6dde4366f6644d00883f9985a333d78b109aa633d4` (62,421,310 bytes).
- Instrumentation: `fe9a3266c289b311a4754b13900600e48c2db4de23eefb2cc96393e2f7a3dba5`.

These are dedicated-emulator debug builds, not release updates. Production still
uses pinned TLS/protocol 4; input freshness/approval, clipboard/paste execution,
bulk files, media capture/rendering and readiness/cutover have not migrated.
Native Windows packaging, physical devices, public TURN and separate/restrictive
networks remain unverified. Cloudflare provisioning and Windows registration
remain deferred. No production route, deployment, version bump or release changed.
The dated checkpoints below identify their own historical APKs.

## Direct transport implementation, 10 October 2026: historical synthetic audio/video checkpoint

The opt-in native proof adds external 10 ms, 48 kHz mono PCM16 sources, bounded
queues, decoded PCM peak checks and aggregate audio RTP counters in both adapters.
Signaling profile 3 binds audio/video/data to the same DTLS/ICE identity; older
proof profiles reject audio. Desktop uses a headless module without physical
sound devices. Android disables hardware AudioRecord before factory creation and
never re-enables it. Its external callback supplies a 10 ms clock because the
selected SDK does not pace that mode. Local audio tracks/sources are disposed
before the factory, then the audio module and owned scheduler are released.
Earlier unpaced/incorrect-order runs failed; the final checks below include both
fixes and retain the original teardown deadlines. Stale PCM is dropped without
closing the peer; future/non-increasing timestamps still fail closed. The audio
checks reject an expired chunk, then verify fresh native audio exchange.

Two existing Stop tests also exposed asynchronous write/read races in the shared
session. Established transport I/O failures now end and revoke the current
session consistently, including queued input/control, media and heartbeat work.
Setup, code/TLS authentication and non-I/O protocol errors retain their behavior.
The two regression checks require Ended and report safe exception-class/location
metadata when it differs.

Final local compilation and both proof APK packages passed. The full Linux JVM
suite ran **71 checks: 61 passed, zero failed, ten skipped** (six existing opt-ins,
Windows decoder integration and three dedicated Android host checks). All twelve
native desktop proof checks passed in 3.810 seconds; all eight session checks
passed. Android-only native data/video/audio instrumentation passed **three checks
in 3.105 seconds** on the final APKs on the dedicated API 36.1/Android 16 emulator.
All three desktop/Android host checks subsequently passed on those exact APKs:

- Data-only: 5.980 seconds.
- Synthetic video: 5.716 seconds, including decoded pixels and RTP counters.
- Synthetic audio/video: 9.447 seconds, including stale-chunk rejection, fresh
  decoded PCM/RTP in both directions, microphone denial and teardown.

These fixture times include installation/setup and are not latency benchmarks.
The installed app/test APK hashes matched the build outputs. Temporary private
configuration and ADB forwarding rules were removed. Failed host runs now stop
their guest instrumentation process as well as the host ADB client.

Final synthetic audio/video proof APK SHA-256 values:

- App: `c722de6a651ca6c209afcdb0aefbdb944dcedf1f953fd0398ef867d25a6d2fcc` (62,421,310 bytes).
- Instrumentation: `f9411015c6e2005fe0df99d2cc5bf3acd9290f7ed9c8e4005b8f84747a09795b`.

The app's `RECORD_AUDIO` permission remained denied before/after audio exchange.
These are dedicated-emulator debug APKs, not compatible release updates. Guest
AudioTrack playback runs without audible host output; this is synthetic decoding
and cleanup evidence, not physical sound quality or device playback capture.
No OS screen/camera/microphone capture, input or file routing was opened by the
native proof. Actual capture/render integration, stereo fidelity, A/V sync,
source/display freshness/epochs, native Windows operation/packaging, hardware
encoding, physical/OEM devices and separate/restrictive networks remain unverified.
Cloudflare signup/provisioning and Windows registration remain deferred by the
owner. No production direct routing, deployment, version bump or release occurred.
The earlier checkpoints below identify their own historical APKs.

## Direct transport implementation, 10 October 2026: historical synthetic video checkpoint

The opt-in proof now feeds bounded raw I420 frames into native video tracks on
both adapters. The Linux desktop proof exchanged authenticated SDP, decoded the
known 96 × 64 grayscale quadrants in both directions, and confirmed at least three
encoded/decoded frames and nonzero RTP bytes on each peer. Stop, plane/stride
bounds and bundled fingerprint/ICE/version substitution checks also passed.

Final local compilation and both APK packages passed. The full JVM suite ran
**67 checks: 58 passed, zero failures, nine skipped** (six existing opt-ins, Windows
decoder integration and two dedicated Android host checks). All nine focused native
proof checks passed. The two host checks were subsequently run separately against
the final APKs on an API 36.1/Android 16 emulator (four GiB RAM, two cores):

- Android-only data/video instrumentation: **two passed in 0.441 seconds**.
- Desktop/Android native data interoperability: **passed in 3.057 seconds**.
- Desktop/Android native video interoperability: **passed in 2.864 seconds**,
  including authenticated TLS bootstrap, synthetic pixels/RTP in both directions
  and teardown. The app's `RECORD_AUDIO` permission remained denied.

Final synthetic-video proof APK SHA-256 values:

- App: `eeb7a0d2e8a2f2940f38f65547dbb9c5b55528f1c43bf7ccc43b75136a5b3e75`.
- Instrumentation: `b5f5a7289c9b186dd140e0d794f5260334e4dee957cdd11e478b9355bbc2e30f`.

These are dedicated-emulator debug APKs, not compatible release updates. Temporary
private fixture configuration and ADB forwarding rules were removed after each
host check. No OS screen/camera/microphone capture or input was opened, and no audio
track was created. This proves an SDK video path, not actual screen/render/input
integration, source/display freshness mapping, native Windows operation, hardware
encoding, physical/OEM compatibility, separate networks, delivered FPS or latency.
The owner chose to defer Windows evaluation registration and Cloudflare signup;
other local work continues. No production routing, version, deployment or release
changed. The earlier data-only checkpoint below identifies its own APKs.

## Direct transport implementation, 10 October 2026: initial data-only checkpoint

The owner resumed implementation, tests, app/emulator checks and audio checks for
this work. No app version was changed or released. `SessionTransport` preserves
production pinned TLS and protocol 4; the native proof is excluded from ordinary
builds. See the [proof commands and dependency inventory](../experimental/README.md).

Local Linux/JDK 17 compilation passed for desktop and Android with one Gradle
worker, a 1536 MiB heap and two visible processors. Both final Android proof APKs
packaged successfully. The full suite ran 62 checks: **54 passed, zero failures,
eight skipped**. Five proof checks passed with real Linux WebRTC JNI in 4.383
seconds: authenticated native binary exchange, Stop during setup, duplicate
negotiation rejection, malformed SDP bounds and signaling context validation.
The portable AVC decoder and all nine broker fixture checks also passed. The
sibling infrastructure baseline passed all ten checks. A later internal TURN
client in [its companion draft](https://github.com/DJProspero1/BetterDesk-Infrastructure/pull/1)
passed all 19 checks, including nine new synthetic provider tests. It is not wired
to the service; no issuance endpoint or production configuration exists.
Normal desktop/Android runtime dependency graphs were checked separately without
the proof property: neither contains WebRTC artifacts.

Skips were six existing native/device/benchmark opt-ins, Windows platform decoder
integration and the dedicated Android interoperability host check. The latter
was then run separately on a dedicated API 36.1/Android 16 emulator with four GiB
RAM and two cores. **Desktop/Android native interoperability passed in 4.833
seconds**, including pinned-TLS bootstrap, context-bound SDP, 16 KiB binary data,
reverse UTF-8 data, instrumentation success and native teardown. The Android-only
native peer test also passed in 0.355 seconds. Both used the exact final APKs below.

Earlier attempts failed during slow emulator startup/installation, then Android
initialization rejected enabling native debug output while an injectable logger
was installed. A boot-readiness guard and bounded installation timeout were added;
the conflicting logging call was removed, retaining the no-op logger. ICE gathering
callbacks now enqueue description work only at COMPLETE. The full 62-check suite
and both Android native checks passed after these fixes. These are synthetic local
data-channel checks, not physical-device, screen-sharing or audio evidence.

Final local proof APK SHA-256 values:

- App: `b6ed31a6ff2604a0f8bc3314c260c5d2765187ec85cc35d59bb8eac7b7769999`.
- Instrumentation: `661920cbc248cd4c101d56255085e973cd45a164aa585a9421f3275b30a2d0b3`.

These are fresh test-emulator debug builds, not same-key updates to the existing
private release. Windows native load/capture/packaging, physical/OEM devices,
separate external networks, TURN reachability, RTP screen/sound, route cutover,
clipboard/files and delivered performance are **untested or not implemented**.
The proof creates no screen/audio source; no new native playback-audio check was
performed. Cloudflare TURN remains selected but needs an owner account/key and
scoped server credential issuance. A local Windows VM was investigated: the retail
media flow returned an error/Access Denied; the official Enterprise evaluation
route requires owner registration with contact/company details. Registration is
pending, and no Windows media/VM was installed. The requested GitHub Codex review
could not run because the account's review usage limit was exhausted; no automated
approval is claimed. No deployment, publication or live infrastructure change occurred.

## Version 0.7.1, 10 October 2026: keyboard fixes

At delivery of these keyboard fixes, tests, app launches, emulator/device checks
and audio workloads were paused by the owner. The pause was lifted for the later
direct-connection implementation; those later checks do not retroactively verify
the delivered Windows binary. Windows and Android are compiled/packaged locally with one Gradle worker
and a 1536 MiB heap. Artifact checks and source identity are recorded in the final
BUILD-INFO.txt/SHA256SUMS.txt. No runtime keyboard or timing result is claimed.

Source review found the viewer only forwarded Ctrl+C/V/A, the receiver silently
discarded inputs less than 20 ms apart, and Android node edits omitted cursor
restoration. The fix adds shared native text/keyboard focus, ordered typing/paste,
Android 13+ accessibility input connections and safer legacy editing. Read the
[keyboard guide](KEYBOARD.md) for the exact scope and deferred device matrix.
IME/layout/OEM compatibility, shortcut delivery, gesture/focus timing, paste races
and physical-device behavior remain **runtime unverified**. Historical Type/Send
checks below are not proof of direct physical-keyboard forwarding.

## Version 0.7, 10 October 2026: files and clipboard

The owner explicitly paused tests and audio workloads. No app, emulator, native
audio fixture, throughput benchmark or automated test was launched for this change.
Both `:desktop:compileKotlin` and `:android:compileDebugKotlin` compiled locally
with one worker and a 1536 MiB Gradle heap. Final local packaging and artifact
checks are recorded in the distribution's BUILD-INFO.txt and SHA256SUMS.txt.

New shared transfer/clipboard code, Windows OLE virtual-file paste, Android document
selection/Share/Downloads, transfer cancellation/retry and silent notification
updates remain **runtime unverified**. No latency, transfer throughput, visual QA
or 60 FPS claim follows from compilation. Existing dated 0.6 measurements below
remain historical evidence and do not verify the 0.7 binaries.

Before treating the feature as production-ready, explicitly authorize and run
targeted checks of bidirectional Explorer paste, folders, clipboard feedback loops,
changed sources, interrupted resume, disk/provider errors, cancel/stop races, and
screen responsiveness during local/relay transfers on physical devices. Include
Android rotation/background/permission behavior and notification removal.

## Version 0.6: sound, screen switching and private release delivery

The ordinary local JVM suite covers 55 checks: 49 enabled and six native/device
checks opt-in. New coverage exercises consent before switching, both request
directions, decline/cancel, repeated reversal, old callbacks/epochs, pending-input
blocking, mute/unmute, denied new capture, PCM conversion and malformed audio sizes.
The Android input policy permits Home/Back from BetterDesk while keeping its own
approval controls and system permission screens protected. That navigation change
has policy coverage; physical-device accessibility navigation remains unverified.

Native Windows WASAPI capture passed with a brief quiet 660 Hz fixture tone,
bounded PCM packets, stop and rapid restart. Android's real AudioPlaybackCapture,
MediaProjection, foreground notification and AVC encoder were exercised through
Railway into the actual Windows decoder/player. A completed audio-enabled run received
1,648 PCM packets; 1,637 contained the known fixture tone. No sound recording was
saved. Remote disconnect stopped projection and removed BetterDesk notifications.

The Android viewer received synthetic Windows video/sound through Railway and its
actual AudioTrack playback head advanced. It rotated, preserved the session, entered
text with the real keyboard, and exposed Exit full screen before opening Controls.
Screenshots verified that exit remains visible in landscape with the keyboard open.
The phone requested a switch, the Windows fixture approved, and Android obtained
a fresh genuine screen-capture grant. Windows then requested a switch back; Android
approved, stopped capture/its sharing notification and resumed viewing on the same
session. Completed native viewer/switch instrumentation passed in 44.468 seconds;
a later viewer run passed in 48.905 seconds.
The harness scrolls genuine native permission pages when their buttons are below
the landscape viewport and checks that the capture dialog actually closes. It
never grants permission through app-ops or Settings writes.

These checks use Windows 11 and the API 36 emulator, controlled/synthetic screen
content and local builds. They do not establish physical/OEM compatibility, actual
Windows OS input in the synthetic reverse fixture, protected-app audio capture,
microphone capture, precise A/V synchronization or input-to-photon latency.

Audio-enabled 30-second relay runs drew 38.65 and 33.67 FPS. A same-build comparison
without audio capture/tone drew 44.60 FPS. This is below the 60 FPS target and below
the historical 0.4 video-only result. Native source logs were also below 60 FPS;
receiver decoding stayed under 1 ms. Audio/network/emulator effects need further
isolation. See [PERFORMANCE.md](PERFORMANCE.md), including bandwidth overhead.

Offline release-script checks verified that invalid staged checksums preserve the
previous distribution, successful replacement removes stale binaries and preserves
unrelated notes, and junction paths are rejected. Git/GitHub doubles verified that
failed asset readback retains the older release and a successful draft retry
downloads/verifies/publishes before deleting superseded releases. Live publication
uses the same guarded script and verifies all four assets by downloaded SHA-256.
All native builds remain local; the repository stays private and the public website
stays a waitlist. These are unsigned Windows/debug-signed Android development builds.

The agent files remain identical, all PowerShell scripts parse, and the local
Markdown links resolve. Reproduction commands and release sequencing are in
[TESTING.md](TESTING.md) and [RELEASING.md](RELEASING.md). Evidence remains under
ignored `desktop/build/reports/`; historical results below refer to earlier versions.

The owner stopped further testing because of local load and audible test playback.
Test apps/audio were stopped. Publication proceeds with `build-local.ps1 -SkipTests`;
no test suite, emulator interaction or sound test is restarted for publication.
The evidence above records completed earlier checks, not a new publication test run.

## Version 0.5: shared black interface and full-screen viewing

The local JVM suite passed 41 enabled checks, with five device/benchmark checks
remaining opt-in. Shared UI checks render phone/desktop home and approval screens,
verify the language flag stores and restores a choice, and resize the viewer from
portrait to landscape. The image fills the maximum aspect-correct area; opening the
controls overlay does not change its bounds. Screenshots were inspected locally.

Android instrumentation verified a flag-selected Portuguese preference survives
activity recreation and a new platform/session instance. A separate live Railway
test connected the actual Android viewer to the controlled Windows fixture, rotated
the phone, checked hidden system bars and full-display image bounds, exited and
re-entered full screen, typed through the real keyboard, sent input and disconnected.
The same session survived rotation and normal status bars returned after disconnect.
This exposed and fixed Android's default keyboard panning: the activity now requests
adjustResize so the landscape text field remains reachable while typing. The final
test passed in 21.207 seconds. Use `:desktop:test --rerun --tests
'*AndroidRelayDeviceTest*Android connects to Windows*'` with
`BETTERDESK_ANDROID_RELAY=1` after installing both local APKs; the Android test APK is
an external input to the host test, so a cached host result is insufficient.

The packaged Windows EXE was opened and its black interface, flag and new window
icon inspected. The portable launcher and embedded application both contain the new
multi-resolution ICO, and the Compose window uses the same vector with an explicit
Windows AppUserModelID. Automated UI interaction was stopped when local user input
intervened; a full Windows close/reopen language check was not completed manually.
The shared preference wiring is covered by the UI test, and Windows stores it with
native per-user preferences. Android persistence coverage is recreation/relaunch,
not a physical-device process-death test.

These are local Windows and API 36 emulator checks. No new FPS result, physical
phone/OEM keyboard coverage, or guarantee about previously pinned Windows shortcuts
is claimed. Builds remain local and source hosting remains private.

Contributor documentation now covers fresh setup, source boundaries, the actual
pairing/wire protocol, platform permissions/data, test reproduction, troubleshooting
and local release preparation. Existing MIT licensing is retained; the dependency
inventory explicitly identifies unfinished redistribution/notice work. All 82 local
Markdown links checked in the root/docs guides resolved on disk.

AGENTS.md, CLAUDE.md and .claude.md have identical SHA-256 hashes and staged Git
blobs. The local build runs a byte-parity check; the tracked pre-commit hook is
enabled in this checkout and compares staged contents. Isolated checks verified
that matching copies pass, a changed mirror fails, explicit synchronization repairs
it, and a deliberately different staged mirror is rejected without modifying the
real index. All local PowerShell scripts parsed without errors. New clones must
enable the hook as documented in CONTRIBUTING.md. No cloud CI was added.

## Version 0.4.1: prefer a reachable local connection

Both apps retain the default local-first policy. New checks cover missing local
peers, discovery socket errors, a discovered device whose reverse screen port is
blocked, and an unresponsive pairing endpoint. Each falls back to the relay before
approval. The existing nearby-peer check now deliberately omits the test override
and verifies the production default selects direct transport. All paths preserve
approval before capture and carry a frame and input after approval.

Additional checks verify the reachability probe uses the authenticated peer IP,
a failed probe leaves the code usable, and cancellation during local pairing cannot
start an Internet request. All 39 enabled checks passed in the ordinary local suite;
five device/benchmark checks remain opt-in. All nine routing integration checks also
passed against the live Railway service.

Two Android instrumentation checks passed for UDP discovery and a complete direct
J-PAKE/TLS session, including consent-before-capture, frames, input and disconnect.
Both endpoints ran inside the emulator using its private interface and synthetic
capture/input. Its pre-existing firewall blocks loopback UDP, so an initial loopback
fixture failed; the final test uses the same wildcard binding and interface discovery
as the app. No firewall or permission bypass was applied. This is Android network-stack
coverage, not a test between two physical devices. Reproduce with
`adb shell am instrument -w -e class app.betterdesk.LocalRouteTest app.betterdesk.test/androidx.test.runner.AndroidJUnitRunner`.

The Android viewer UI test also passed against the live Railway service with the
local-first default enabled: it connected to the controlled Windows fixture, received
frames, sent input through Type/Send and disconnected. The 0.4.1 portable Windows EXE
and Android APK were built locally; no binaries were published.

No physical home Wi-Fi test or new FPS measurement is claimed.
Update both devices to 0.4.1 for the improved blocked-port fallback.

## Version 0.4: native app integration with Railway

All native builds, tests, signing and packaging ran on the local Windows PC.
The public relay runs on Railway in EU West. No development server or cloud app
build was used, and no app binaries are published for download.

## Automated checks

The ordinary build passed 32 checks, including five against the real Node broker
running as a temporary loopback test fixture. Five opt-in device/benchmark checks
are skipped in this suite. The two new Android relay checks were run separately
against the public Railway service and both passed. The five transport integration
checks also passed against the public service.

Coverage includes code-authenticated pairing, certificate pinning, consent before
capture, malformed/oversized messages, stale input rejection, codec recovery,
wrong private digits, retry, decline, cancellation of a remote approval, direct-LAN
preference for an Internet code, and local-only fallback when registration fails.
Relay invitations cannot select a hostname or enter through an external link.
Phone and desktop UI tests render the same twelve-digit code flow.

The infrastructure repository separately passes ten tests, including cancellation
before approval and after joining, one-use tickets, bounds, waitlist consent,
duplicate privacy and removal behavior.

## Actual Android integration

The existing API 36 emulator was updated with the local APK. A separate test APK
uses Android UI Automator to press the real connection, notification and
MediaProjection prompts; no app-ops permission bypass is used.

- **Android screen to Windows:** the real Android capture/AVC encoder, public
  Railway TCP relay, pinned TLS, Windows native decoder and Compose viewer were
  exercised together. The test switched to an animated fixture app while sharing.
  Remote disconnect stopped capture and removed BetterDesk notifications.
- **Android viewer to Windows:** Android entered the code, received live JPEG
  frames through Railway, sent text using the actual Type/Send controls and
  disconnected. A controlled Windows fixture generated a moving image and
  received input, avoiding capture or input in unrelated PC applications. This
  proves the Android viewer/transport/input path, not actual Windows OS input.

This testing found and fixed an Android UI-thread network write in the final
connection acceptance. The reverse path now passes with Android's normal
network-on-main-thread enforcement enabled.

## Measured performance

The public-relay measurement used 30 seconds of continuous Android motion after
warmup. Received FPS counts decoded frames. Drawn FPS counts distinct frame IDs
submitted to Compose drawing, not physical display scanout or input latency.

| Path | Received FPS | Drawn FPS | P95 draw gap | Encoded payload |
| --- | ---: | ---: | ---: | ---: |
| Android emulator to Windows through Railway | 57.42 | 56.52 | 34 ms | 2.11 Mbps |

The relay acknowledgement window increased from eight to twelve bounded frames;
the earlier relay run drew 48.56 FPS. Native decoding averaged 0.88 ms in the final
run. Android used a software AVC encoder at 432 x 960 on a nominal 60 Hz emulator.
This result is below 60 FPS and is not a physical-phone performance claim.

Previous 0.3 measurements on 8 October used direct connections: Windows loopback
drew 62.57 FPS, and Android emulator to Windows drew 59.87 FPS. They were not
remeasured as part of this relay integration. Details are in PERFORMANCE.md.

## Local outputs and packaging

The single portable Windows EXE includes its runtime and automatically expands it
into a private content-addressed cache. No installation, external Java or manual
ZIP extraction is required. Android is a debug-signed APK update using the same
local development key. Both devices must run the updated app.

The packaged Windows libraries and matching bundled runtime passed live HTTPS
trust, relay registration/revocation and ephemeral TLS identity checks. Reports,
device screenshots and build hashes stay in ignored local build/dist directories.
The separate instrumentation and motion-fixture APKs are never bundled with the app.
The final portable 0.4.0 EXE opened from its embedded cache, displayed a real
twelve-digit Internet code and closed normally through its window Close button.
Final Android readbacks showed no active MediaProjection or BetterDesk notification.

## Reproduce

Run `scripts/build-local.ps1` through the checked ASCII workspace junction. It
automatically uses an adjacent BetterDesk-Infrastructure broker fixture when
present; otherwise set `BETTERDESK_BROKER_FIXTURE` to its absolute path. The fixture
terminates when the test closes stdin.

For live transport checks set `BETTERDESK_RELAY_LIVE=1` and run
`:desktop:test --tests app.betterdesk.RelayIntegrationTest --rerun-tasks`.
For device tests, build/install `:android:assembleDebugAndroidTest` and the separate
`:motion-fixture:assembleDebug` APK alongside BetterDesk. Set
`BETTERDESK_ANDROID_RELAY=1` and run
`:desktop:test --tests app.betterdesk.AndroidRelayDeviceTest --rerun-tasks`.
These device tests force Internet relay routing even on a shared local network.
The default device is `emulator-5554`; `BETTERDESK_ANDROID_SERIAL` overrides it.

## Remaining limits

- Sustained 60+ FPS, input-to-photon latency and full control on physical phones.
- Android vendor differences, thermal throttling, work profiles and older versions.
- Actual Windows screen capture/control over a mobile-data connection, UAC,
  monitor changes and mixed DPI. Windows OS capture/input was not exercised by
  the synthetic reverse-direction fixture.
- Slow/lossy Internet, restrictive corporate networks and long sessions.
- TCP head-of-line delay, no UDP/TURN or port-443 tunnel fallback, no adaptive
  bitrate, and Windows hardware video encoding still pending.
- The singleton relay has two-hour/2 GiB limits. Restart or network loss ends
  the session; reconnecting requires a new attended exchange.

Passing these checks does not establish production readiness or universal 60 FPS.
