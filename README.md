# BetterDesk

Simple, attended remote help for Windows and Android. Both applications use the **same Kotlin/Compose interface, copy, session state machine, and encrypted transport**. Only capture, input, permissions and packaging are platform-specific.

The source is hosted in a private GitHub repository during development. All native compilation, tests, and packaging happen locally. There are no GitHub Actions, cloud app builds, telemetry or remote fonts. Railway hosts the connection service; the separate website remains a waitlist with no downloads.

## Current milestone

Version 0.4 is an **early Internet-connected development build**, not a production remote-support release. Connections prefer a reachable local network and otherwise use the BetterDesk Railway relay. It has not been externally security-audited. Do not distribute it to ordinary clients yet.

- Windows and Android have the same home screen: this device's code, another device's code, and Connect.
- Enter the other device's twelve-digit code. Its owner explicitly approves sharing. If the connection service is unavailable, an eight-digit code is available for the local network.
- LAN discovery or Internet routing plus password-authenticated pairing replaces copy/pasted invitation links. No account or server setup is needed in the apps.
- Local connections are the default on both platforms. Before displaying approval, the requested device checks that it can reach the other device's screen port. Blocked ports, discovery failures or absent peers fall back to Railway automatically. Local discovery/pairing gets a 3.5-second budget; no network selector is shown. A successful local session sends screen and control traffic directly, although Internet code registration still contacts Railway.
- Shared desktop/mobile UI with English and European Portuguese copy.
- Direct TLS connection with certificate pinning, a random 256-bit invitation secret, five-minute invitation expiry, one recipient, and no persistent identity or permanent access.
- Native Windows Desktop Duplication capture, with a compatible GDI fallback. Streaming keeps bounded queues and uses an acknowledgement window of four frames locally or twelve through the relay. See [performance measurements](docs/PERFORMANCE.md) for measured results and limits; 60 FPS is not guaranteed.
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

- **Windows: `dist/BetterDesk-Windows-0.4.1.exe`**. Double-click. No installer, ZIP extraction or separate Java installation.
- **Android: `dist/BetterDesk-Android-0.4.1-debug.apk`**. Install as an update over the previous APK; the same local debug signing key is used.
- Local checks: `desktop/build/reports/tests/test/index.html`.
- Rendered UI checks: `desktop/build/screenshots/`.

The script uses a checked local directory junction with an ASCII path for JDK packaging compatibility (for example when the source folder is `Programação`). Source stays in this checkout. It does not start a development server, upload code, or run cloud builds.

The portable EXE contains the app and Java runtime. On first opening each build it automatically expands its private files into `%LOCALAPPDATA%/BetterDesk/portable/<content-hash>/`, then launches. Subsequent opens reuse those files. There is no installer, registry association or admin prompt; internal cached files are necessary for the native runtime. Older build caches remain until manually removed when no instance is running. Keep only one BetterDesk window open.

The Android APK uses the local debug signing key. The Windows build is unsigned. Consumer release signing remains later work. Downloading a new APK still requires Android's update confirmation; ordinary apps cannot silently replace themselves.

## Try a session

1. Install/open the updated app on both devices. Internet connections need HTTPS and the Railway relay's outgoing TCP port; no router forwarding is required. Windows firewall may require allowing direct LAN connections on a private network; do not disable the firewall.
2. Each device shows its own code. On either device, enter the other device's code and select **Connect**.
3. The requested device selects **Allow connection**. No role selection or clickable link is involved.
4. On Android, follow the short control instructions or choose **Share my screen only**. If Android blocks the setting, select **Android blocked this?** for the App info steps. Then confirm the genuine screen-sharing prompt.
5. Select **Disconnect**, or use Android's Stop notification. Switching apps and returning keeps the connection; swiping BetterDesk out of Recents ends it. Pending codes expire after five minutes and can be renewed with **Get a code**.

Treat codes as temporary secrets. A displayed device name is a label, not a verified real-world identity. Approve only an expected connection.

## Known limits

- No unattended access, recording, file transfer, automatic clipboard sync or microphone access.
- Broadcast discovery needs a shared reachable IPv4 LAN. Guest Wi-Fi, client isolation, VPNs and firewalls can block it; a twelve-digit code then falls back to the Internet relay. An eight-digit fallback code only works locally.
- Internet sessions use a TCP relay in EU West. Restrictive networks may block its external TCP port. There is no UDP/TURN, HTTPS-tunnel fallback, direct Internet peer-to-peer negotiation or automatic network handover. Relay restart stops active sessions. Sessions are capped at two hours or 2 GiB, whichever comes first.
- Automatic reconnect is not implemented. On network interruption, sharing stops and a new code is required. Switching apps alone should not close the session; force stop and manufacturer-specific battery restrictions may still terminate it.
- Android screen lock/revoked capture ends sharing. Protected content may be blank. Android control refuses BetterDesk, Settings, permission-controller and system-UI screens, and locked devices.
- Windows captures the primary display. It does not bypass UAC or secure desktop. Remote input into BetterDesk's own windows is blocked.
- Windows hardware video encoding, adaptive bitrate, multi-monitor selection and precise mobile trackpad/zoom controls remain future work. Android can encode AVC directly from capture; Windows uses native decoding. This is not yet a claim of AnyDesk-level latency or bandwidth efficiency.
- Direct touch/drag controls are present; touchscreen Windows desktop precision still needs usability work.
- Android text uses the focused editable accessibility node; custom keyboards, games, password fields or apps without accessibility support may not work.
- Capture and stop behavior needs physical-device testing across Android vendors. Desktop JVM packaging favors shared code and reliable local builds over a small download; optimize package size after proving the workflow.

See [architecture](docs/ARCHITECTURE.md), [security](SECURITY.md), [product](PRODUCT.md), and [design](DESIGN.md).
