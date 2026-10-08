# BetterDesk

Simple, attended remote help for Windows and Android. Both applications use the **same Kotlin/Compose interface, copy, session state machine, and encrypted transport**. Only capture, input, permissions and packaging are platform-specific.

The source is hosted in a private GitHub repository during development. All compilation, tests, and packaging happen locally. There are no GitHub Actions, cloud builds, telemetry, hosted signaling services or remote fonts.

## Current milestone

This is an **early local-network implementation**, not a production remote-support release. The helper and the person receiving help must be on the same reachable network. It has not been externally security-audited. Do not distribute it to ordinary clients yet.

- Windows and Android have the same home screen: this device's code, another device's code, and Connect.
- Enter the other device's eight-digit code. Its owner explicitly approves sharing.
- LAN discovery plus password-authenticated pairing replaces copy/pasted invitation links.
- Shared desktop/mobile UI with English and European Portuguese copy.
- Direct TLS connection with certificate pinning, a random 256-bit invitation secret, five-minute invitation expiry, one recipient, and no persistent identity or permanent access.
- Native Windows Desktop Duplication capture, with a compatible GDI fallback. The Windows path exceeded 60 distinct frames per second in a local motion benchmark; Android remains below target in the emulator. Streaming has a four-frame acknowledgement window to bound buffering. See [performance measurements](docs/PERFORMANCE.md) for scope and limits.
- Android uses genuine MediaProjection approval and an optional accessibility service.
- Screen sharing stays view-only when control is unavailable.
- Android asks for notification permission and shows a sharing notification with **Disconnect**. It disappears when sharing ends; the availability notification is removed when capture takes over. Switching apps preserves the session. Control-permission screens explain the steps, including restricted-settings recovery.
- Stop closes the transport and capture. Reusing a closed invitation does not reopen access.

## Build locally on Windows

Prerequisites: JDK 17, Android SDK platform 36/build tools, and accepted Android SDK licenses. The Gradle wrapper downloads pinned dependencies on the first build. No code is uploaded by building.

Create ignored `local.properties` with `sdk.dir=C:/path/to/Android/Sdk`.

```powershell
.\scripts\build-local.ps1
```

Outputs:

- **Windows: `dist/BetterDesk-Windows-0.3.0.exe`**. Double-click. No installer, ZIP extraction or separate Java installation.
- **Android: `dist/BetterDesk-Android-0.3.0-debug.apk`**. Install as an update over the previous APK; the same local debug signing key is used.
- Local checks: `desktop/build/reports/tests/test/index.html`.
- Rendered UI checks: `desktop/build/screenshots/`.

The script uses a checked local directory junction with an ASCII path for JDK packaging compatibility (for example when the source folder is `Programação`). Source stays in this checkout. It does not start a development server, upload code, or run cloud builds.

The portable EXE contains the app and Java runtime. On first opening each build it automatically expands its private files into `%LOCALAPPDATA%/BetterDesk/portable/<content-hash>/`, then launches. Subsequent opens reuse those files. There is no installer, registry association or admin prompt; internal cached files are necessary for the native runtime. Older build caches remain until manually removed when no instance is running. Keep only one BetterDesk window open.

The Android APK uses the local debug signing key. The Windows build is unsigned. Consumer release signing remains later work. Downloading a new APK still requires Android's update confirmation; ordinary apps cannot silently replace themselves.

## Try a session

1. Install/open the app on both devices on the same network. Windows firewall may require allowing the app on a private network; do not disable the firewall.
2. Each device shows its own code. On either device, enter the other device's code and select **Connect**.
3. The requested device selects **Allow connection**. No role selection or clickable link is involved.
4. On Android, follow the short control instructions or choose **Share my screen only**. If Android blocks the setting, select **Android blocked this?** for the App info steps. Then confirm the genuine screen-sharing prompt.
5. Select **Disconnect**, or use Android's Stop notification. Switching apps and returning keeps the connection; swiping BetterDesk out of Recents ends it. Pending codes expire after five minutes and can be renewed with **Get a code**.

Treat codes as temporary secrets. A displayed device name is a label, not a verified real-world identity. Approve only an expected connection.

## Known limits

- No Internet rendezvous, NAT traversal, relay, global short codes, unattended access, recording, file transfer, automatic clipboard sync or microphone access.
- Broadcast discovery needs a shared reachable IPv4 LAN. Guest Wi-Fi, client isolation, VPNs and firewalls can block it. Emulator NAT does not simulate same-LAN broadcast; the device integration fixture uses a pinned direct invitation internally.
- Automatic reconnect is not implemented. On network interruption, sharing stops and a new code is required. Switching apps alone should not close the session; force stop and manufacturer-specific battery restrictions may still terminate it.
- Android screen lock/revoked capture ends sharing. Protected content may be blank. Android control refuses BetterDesk, Settings, permission-controller and system-UI screens, and locked devices.
- Windows captures the primary display. It does not bypass UAC or secure desktop. Remote input into BetterDesk's own windows is blocked.
- Windows hardware video encoding, adaptive bitrate, multi-monitor selection and precise mobile trackpad/zoom controls remain future work. Android can encode AVC directly from capture; Windows uses native decoding. This is not yet a claim of AnyDesk-level latency or bandwidth efficiency.
- Direct touch/drag controls are present; touchscreen Windows desktop precision still needs usability work.
- Android text uses the focused editable accessibility node; custom keyboards, games, password fields or apps without accessibility support may not work.
- Capture and stop behavior needs physical-device testing across Android vendors. Desktop JVM packaging favors shared code and reliable local builds over a small download; optimize package size after proving the workflow.

See [architecture](docs/ARCHITECTURE.md), [security](SECURITY.md), [product](PRODUCT.md), and [design](DESIGN.md).
