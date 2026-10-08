# Local verification, 8 October 2026

## Automated checks

The complete local suite passed 12 checks with no failures. The additional opt-in Android integration check passed separately (it is deliberately skipped in an ordinary build). Both the Windows distributable and Android debug APK were built locally. The bundled Windows executable also launched successfully and was closed after the smoke check.

The local desktop JVM suite covers invitation parsing/expiry, invalid destinations, duplicate fields, input bounds, actual TLS message/frame transfer, certificate mismatch rejection, oversized frames, approval-before-capture, input revocation after Stop, invitation cancellation, and the same UI at desktop and phone sizes.

UI tests render the actual shared Compose components, rather than separate mockup images. Screenshots are generated under ignored `desktop/build/screenshots/`.

## Android emulator

The debug APK was installed and launched on the already-running Android emulator. Receiving-help and helper-invitation screens were inspected. Android successfully generated a temporary TLS identity and a live invitation.

An opt-in integration test then created a helper on the Windows host, opened its invitation on Android, and waited while the app's local approval and Android's genuine MediaProjection dialog were operated. The device shared its full screen in view-only mode. The Windows test received and decoded actual Android screen frames over the pinned TLS connection, saved a local screenshot, and closed the connection.

The opt-in test is `AndroidDeviceTest`; it runs only with `BETTERDESK_ANDROID_TEST=1`. It requires an installed debug APK, the configured emulator (default `emulator-5554`), and interaction with the consent prompts. It binds to host loopback and uses the emulator's `10.0.2.2` host alias. It never captures the PC screen. It must not be enabled accidentally during ordinary builds.

## Not yet verified

- Full remote control in both directions on physical Windows/Android devices.
- Multiple Android manufacturers, old Android versions, work profiles and policy-managed devices.
- Windows UAC/elevated targets, monitor changes, mixed DPI and corporate firewall configurations.
- Signed installers, consumer distribution, Internet traversal and relays.
- Long sessions, network roaming and performance under sustained load.

Successful compilation and the emulator test do not establish production readiness. The delivered builds remain local-network development artifacts.
