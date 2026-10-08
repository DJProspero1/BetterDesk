# BetterDesk

Simple, attended remote help for Windows and Android. Both applications use the **same Kotlin/Compose interface, copy, session state machine, and encrypted transport**. Only capture, input, permissions and packaging are platform-specific.

The source is hosted in a private GitHub repository during development. All compilation, tests, and packaging happen locally. There are no GitHub Actions, cloud builds, telemetry, hosted signaling services or remote fonts.

## Current milestone

This is an **early local-network implementation**, not a production remote-support release. The helper and the person receiving help must be on the same reachable network. It has not been externally security-audited. Do not distribute it to ordinary clients yet.

- Windows and Android can create invitations and receive help.
- Recipient opens/pastes an invitation and explicitly approves sharing.
- Shared desktop/mobile UI with English and European Portuguese copy.
- Direct TLS connection with certificate pinning, a random 256-bit invitation secret, five-minute invitation expiry, one recipient, and no persistent identity or permanent access.
- JPEG screen streaming, remote tap/click, drag/swipe, scroll, text entry and basic navigation.
- Android uses genuine MediaProjection approval and an optional accessibility service.
- Screen sharing stays view-only when control is unavailable.
- Stop closes the transport and capture. Reusing a closed invitation does not reopen access.

## Build locally on Windows

Prerequisites: JDK 17, Android SDK platform 36/build tools, and accepted Android SDK licenses. The Gradle wrapper downloads pinned dependencies on the first build. No code is uploaded by building.

Create ignored `local.properties` with `sdk.dir=C:/path/to/Android/Sdk`.

```powershell
.\scripts\build-local.ps1
```

Outputs:

- Windows application folder: `desktop/build/compose/binaries/main/app/BetterDesk/`. Run `BetterDesk.exe` from this folder; it needs the adjacent bundled runtime.
- Android development APK: `android/build/outputs/apk/debug/android-debug.apk`.
- Local checks: `desktop/build/reports/tests/test/index.html`.
- Rendered UI checks: `desktop/build/screenshots/`.

The script uses a checked local directory junction with an ASCII path for JDK packaging compatibility (for example when the source folder is `Programação`). It also places a Windows ZIP and Android APK in ignored `dist/`. The source stays in this checkout. It does not start a development server, upload code, or run cloud builds.

The Android APK uses the local debug signing key. The Windows build is unsigned. Consumer release signing and installers are later work.

## Try a session

1. Install/open the app on both devices on the same network. Windows firewall may require allowing the app on a private network; do not disable the firewall.
2. Helper selects **I'm here to help someone**, enters their name, creates and copies an invitation, and sends it using an existing conversation. That conversation is outside BetterDesk.
3. Recipient opens the Android `betterdesk://` link or explicitly copies it and selects **Paste invitation** in either app.
4. Recipient checks the sender and chooses **Allow help**. Android can enable control or choose screen sharing only, then displays its native sharing prompt.
5. Recipient can stop in BetterDesk or through the Android sharing notification/system controls. Closing the Windows app stops its session.

Treat invitations as secrets. A displayed helper name is a label, not a verified real-world identity. Use a trusted channel to exchange the invitation.

## Known limits

- No Internet rendezvous, NAT traversal, relay, global short codes, unattended access, recording, file transfer, automatic clipboard sync or microphone access.
- Links do not survive installation automatically. Windows URL registration is not installed; paste invitations into the running Windows app.
- Automatic reconnect is not implemented. On interruption, sharing stops and a new invitation is required.
- Android screen lock/revoked capture ends sharing. Protected content may be blank. Android control refuses BetterDesk, Settings, permission-controller and system-UI screens, and locked devices.
- Windows captures the primary display. It does not bypass UAC or secure desktop. Remote input into BetterDesk's own windows is blocked.
- Current image streaming is approximately five frames per second. Hardware video codecs, adaptive bitrate, multi-monitor selection and precise mobile trackpad/zoom controls remain future work.
- Direct touch/drag controls are present; touchscreen Windows desktop precision still needs usability work.
- Android text uses the focused editable accessibility node; custom keyboards, games, password fields or apps without accessibility support may not work.
- Capture and stop behavior needs physical-device testing across Android vendors. Desktop JVM packaging favors shared code and reliable local builds over a small download; optimize package size after proving the workflow.

See [architecture](docs/ARCHITECTURE.md), [security](SECURITY.md), [product](PRODUCT.md), and [design](DESIGN.md).
