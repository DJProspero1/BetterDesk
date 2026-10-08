# Local verification, 8 October 2026

## Automated checks

The 0.2 local suite contains 19 checks. Two additional checks are opt-in: native Windows capture measurement and real Android emulator integration. Both the Windows distributable and Android debug APK are built locally. The single portable Windows executable was launched successfully from its embedded, automatically extracted runtime and closed after the smoke check.

The JVM suite covers invitation parsing/expiry, invalid destinations, duplicate fields, input bounds, actual TLS message/frame transfer, certificate mismatch rejection, oversized frames, approval-before-capture, input revocation after Stop, cancellation, and the same UI at desktop and phone sizes. Added coverage checks J-PAKE correct/wrong codes, single-use/canceled codes, bounded frames in flight, the full discovered-code -> approval -> frame -> disconnect path, and disconnect during continuous capture. Uncaught session exceptions now fail the lifecycle tests.

UI tests render the actual shared Compose components, rather than separate mockup images. Screenshots are generated under ignored `desktop/build/screenshots/`.

## Android emulator

The debug APK was installed and launched on the already-running Android emulator. The shared code screen and permission guidance were inspected. Leaving to the home screen and returning preserved the same code. A connected-device foreground service remained active while the activity was in the background.

The opt-in integration test creates a viewer on the Windows host, passes an internal invitation to Android, and waits for local approval plus Android's genuine MediaProjection dialog. The device shares its full screen in view-only mode. The test opens Android Settings, performs repeated scroll gestures, returns to BetterDesk, and checks the stream remains connected. It also checks that remote disconnect releases MediaProjection and preserves the Android process. This final check was added after testing exposed an uncaught coroutine failure on peer disconnect; socket reader/writer children now run within a caught structured scope.

The opt-in test is `AndroidDeviceTest`; it runs only with `BETTERDESK_ANDROID_TEST=1`. It requires an installed debug APK, the configured emulator (default `emulator-5554`), and interaction with the consent prompts. It binds to host loopback and uses the emulator's `10.0.2.2` host alias. It never captures the PC screen. It must not be enabled accidentally during ordinary builds.

## Performance measurements

- Previous source imposed a 180 ms delay between captures, limiting streaming to approximately five FPS before processing costs.
- New capture pacing targets 30 FPS. Native Windows capture measured 24.0 FPS overall and after warmup in a 12-second sample on this PC. The benchmark stores only timing and encoded-byte counts, not images.
- The final Android emulator -> Windows scrolling sample reached 20.6 received FPS with native Skia decoding. This includes gaps between adb-driven scroll gestures, not just active animation. Results are recorded in ignored `desktop/build/reports/android-motion.txt`. The Android process survived remote disconnect and a native readback confirmed MediaProjection was stopped and no foreground sharing/connection services remained.
- Two encoded frames at most can be in flight. Slow viewers cause old captures to be dropped instead of an ever-growing video queue.

These are sample throughput measurements, not input-to-display latency measurements or a 30 FPS guarantee. Smooth production remote control remains unresolved; hardware video, native capture and physical-device/network measurements are still required.

## Not yet verified

- Full remote control in both directions on physical Windows/Android devices.
- Multiple Android manufacturers, old Android versions, work profiles and policy-managed devices.
- Windows UAC/elevated targets, monitor changes, mixed DPI and corporate firewall configurations.
- Signed installers, consumer distribution, Internet traversal and relays.
- Long sessions, network roaming and performance under sustained load.

Successful compilation and the emulator test do not establish production readiness. The delivered builds remain local-network development artifacts.
