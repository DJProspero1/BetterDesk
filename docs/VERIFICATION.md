# Local verification, 8 October 2026

## Version 0.3

All tests, benchmarks, compilation and packaging run on the local Windows PC.
No cloud build or development server is used. Outputs remain development builds for
reachable local networks, with the same shared Windows/Android interface.

## Automated checks

All 24 ordinary JVM checks passed; three opt-in checks were skipped by the normal
build and the two motion/integration benchmarks were run separately. The suite covers
authenticated pairing, certificate pinning, malformed
and oversized packets, consent before capture, disconnect/cancellation, bounded input
and frame queues, stale-frame input rejection, and shared UI rendering at desktop and
phone sizes. New checks cover native AVC decoding and pixel correctness, its LGPL
library variant, predictive-frame recovery after drops, asynchronous acknowledgement
waiting, and unchanged versus stalled capture heartbeats.

Capture benchmarks are opt-in and skipped in ordinary builds. Reports and UI images
are generated under ignored desktop/build directories. The motion-fixture Android
module is a separate test app and is never packaged into BetterDesk.

## Measured streaming

Both measurements used continuous motion for 30 seconds after warmup. Drawn FPS counts
distinct decoded frame IDs submitted to Compose drawing, not repeated UI repaints.
This does not measure physical display scanout or input-to-photon latency.

| Path | Received FPS | Drawn FPS | P95 draw gap | Encoded payload |
| --- | ---: | ---: | ---: | ---: |
| Windows capture to Windows viewer, pinned TLS loopback | 62.57 | 62.57 | 21 ms | 37.96 Mbps |
| Android emulator to Windows viewer, pinned TLS | 59.94 | 59.87 | 24 ms | 1.65 Mbps |

Windows: RTX 4070, 2560 x 1440 display at 144 Hz, Desktop Duplication resized to
1440 x 810, JPEG. Android: API 36 emulator, 1080 x 2400 at 60 Hz, software AVC encoder
at 432 x 960, native Windows AVC decode averaging 0.87 ms. Hardware Android encoders
retain a 1280-pixel long edge. The lower software resolution favors motion over detail.

The earlier Windows path measured about 24 FPS; Android JPEG measured about 30 FPS
under continuous motion. Intermediate AVC runs drew 40-57 FPS before real-time codec
scheduling. The final Android result is approximately nominal 60 Hz, but is still below
a literal 60 drawn-FPS minimum. Physical phones and real Wi-Fi have not been measured.
See PERFORMANCE.md for pipeline details and local reproduction commands.

## Android lifecycle and notification

The updated APK was installed over the previous version on the already-running local
emulator. Genuine connection approval, Android notification permission and MediaProjection
consent were used. The integration test switched to a separate animated app, streamed
its screen, and returned to BetterDesk without losing the connection.

Native readbacks verified the screen-sharing notification exists during capture and the
obsolete availability notification is absent. Both remote disconnect and the actual
notification Disconnect action stopped MediaProjection, removed all BetterDesk
notifications, and left the same Android process alive. Android can still hide app
notifications if the user denies notification permission or disables its channel.

## Packaging verification

The local build produces a single portable Windows EXE and Android debug APK. The
Windows launcher embeds its runtime and expands it automatically into a private local
cache; the user does not install Java or unpack a ZIP. Both devices should be updated.
Native AVC decoding also passed against the packaged application JARs and a copy of
the bundled runtime, using the launcher's configured Skiko library path.
The portable 0.3.0 EXE opened the BetterDesk window from its embedded runtime cache
and closed normally after the smoke check. Final native Android readback showed no
active MediaProjection or leftover BetterDesk notification.

## Still unverified

- Sustained 60+ FPS and full remote control in both directions on physical devices.
- End-to-end input latency, thermal throttling, real Wi-Fi loss and long sessions.
- Different Android vendors, older versions, work profiles and policy-managed devices.
- Windows UAC/elevated targets, monitor changes, mixed DPI and corporate firewalls.
- Internet traversal, adaptive bitrate and Windows hardware video encoding.

Successful local tests do not establish production readiness or universal 60 FPS.
