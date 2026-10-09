# Local verification, 9 October 2026

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
