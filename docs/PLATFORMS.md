# Platform behavior, permissions and troubleshooting

This describes version 0.5.0. The shared interface is the product contract; native
differences exist where the OS requires them. The verified environments are
Windows 11 x64 and an Android API 36 emulator. Declared minimum Android API 26 is
not a claim of complete vendor/version coverage.

## Shared experience

Open the app, enter another device's code, connect, and approve on that device.
There is no role selection, account or link-sharing requirement. Codes are
short-lived; a displayed name is not a verified identity. Only an expected person
should be approved. Offline codes have eight digits; online codes have twelve.

Both apps use black/charcoal UI, the same connection icon and English/European
Portuguese copy. The language flag selects a saved preference; first launch uses
the system language (`pt`, otherwise English). Windows flag drawing does not
depend on emoji fonts. Preferences are local to each installation/user, not synced
between devices.

Viewing enters full screen without an app header or permanent toolbar. The image
fits without stretching or cropping, so unlike display shapes still leave black
margins. **Controls** opens an overlay; **Hide** closes it. Disconnect, Type,
scroll and navigation controls depend on the remote capability. Full-screen exit
restores the native window/bars. Overlay controls respect safe areas and keyboard
insets; the Android activity requests `adjustResize` for landscape typing.

## Windows

- The portable x64 EXE needs no installer or separate Java installation. It expands
  its embedded runtime once per content hash into the current user's local cache.
  It is unsigned; release signing and a trusted updater are not implemented.
- Capture uses D3D11/DXGI Desktop Duplication on the primary display, with GDI
  fallback. Windows currently transmits JPEG. Android-to-Windows can use AVC
  with native FFmpeg decoding. Multi-monitor selection and Windows hardware video
  encoding are not implemented.
- Input uses AWT mouse events and native Unicode keyboard input. It cannot
  bypass UAC/secure desktop or operate BetterDesk's own approval windows. Do not
  request elevation merely to hide an unsupported-control limitation.
- Direct LAN connections can require a Windows firewall exception on the
  intended private network. Do not disable the firewall. Outbound relay traffic
  is the fallback when available; restrictive networks may block its TCP port.
- Window, launcher and packaged executable icons use the same generated artwork;
  the window has AppUserModelID `app.betterdesk.desktop`. An old pinned shortcut
  can still point to a previous executable/cache. Close the old instance and
  unpin/re-pin the current one rather than assuming a new binary updates old pins.
- Keep one BetterDesk instance open. Close the window to stop the desktop session;
  minimizing it is not disconnecting.

## Android

| Native capability | Why it exists / behavior |
| --- | --- |
| MediaProjection | Genuine per-session screen-capture consent; denial leaves no authorized capture |
| AccessibilityService | Optional input gestures/text; grant through real Settings; view-only remains available |
| Notification permission (API 33+) | Requested before the separate sharing prompt; denial does not grant capture |
| Media-projection foreground service | Sharing lifetime, visible sharing notice and Disconnect action |
| Connected-device foreground service | Availability/session continuity while changing apps; replaced when capture starts |
| Partial wake lock | Connection-service lifetime only; released on teardown |
| Internet/network permissions | HTTPS, relay TCP and direct LAN; network-state permissions support the connected-device service |
| WindowInsetsController / edge-to-edge | Native full-screen viewer, transient bars by swipe, cutout and keyboard handling |

The manifest declares `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE`,
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`,
`FOREGROUND_SERVICE_MEDIA_PROJECTION`, `WAKE_LOCK` and `POST_NOTIFICATIONS`.
`BIND_ACCESSIBILITY_SERVICE` protects the service binding; it is not an ordinary
runtime prompt. There is no microphone, contacts, file-storage or camera grant.

Sideloaded APKs may have accessibility restricted by Android. BetterDesk explains
the steps before opening Settings and includes **Android blocked this?** recovery
through its App info page. Only the device owner can allow restricted settings;
the app cannot legitimately do it silently. Labels/menus vary by OS and vendor.
See [Android's restricted-settings guidance](https://support.google.com/android/answer/12623953).

Android control requires unlocked full-display capture and an active accessibility
service. It refuses BetterDesk, Settings, permission-controller and system-UI
screens. App-only capture cannot safely authorize whole-display coordinates.
Text targets the focused editable accessibility node; custom renderers, games,
some password fields and unsupported widgets can reject it. Protected content may
appear blank. Screen lock/revoked projection stops sharing.

Switching apps, returning and activity rotation preserve the process-held session.
Removing the app from Recents, force stopping it or process death ends access;
there is no unattended restart or automatic session recovery. Manufacturer battery
policies can still kill the process. Do not claim a wake lock defeats those policies.

During sharing, notification ID 1/channel `screen` includes Disconnect. The earlier
availability notification ID 2/channel `connection` is removed when capture starts.
Teardown explicitly removes notifications. If notification permission/channel is
disabled, Android controls what appears in the drawer; explain that before promising
a visible notice. Current notification text follows the **system locale**, while
the in-app preference follows the selected flag; aligning notification localization
with that preference remains a known gap. OS consent dialogs also follow system
localization and remain native.

## Local data and privacy

| Data | Location / lifetime |
| --- | --- |
| Android language (`en`/`pt`) | Private SharedPreferences `appearance`, key `language`; same-app updates retain it; clearing data/uninstall resets it |
| Windows language | Java per-user Preferences node `app/betterdesk`, key `language`; independent of the versioned portable cache |
| Windows extracted runtime/app | `%LOCALAPPDATA%/BetterDesk/portable/<content-hash>/`; previous caches remain until manually removed while stopped |
| Codes, keys, invitations, tickets | Ephemeral process/connection state; not stored as future access credentials |
| Screen/input content | Live endpoint memory and encrypted transport; no app recording/history/telemetry |
| Local development outputs | Ignored `build/` and `dist/`; tests can save synthetic screenshots and timing reports |

Android backup is disabled in the manifest. OS-managed accessibility/notification
permissions are distinct from the language preference and remain controlled by
Settings. Windows Java Preferences uses the runtime's native per-user store; the
portable app therefore has an extraction cache **and** a saved preference even
though it has no installer. Removing an EXE alone does not erase them.

The relay sees network/routing metadata and encrypted bytes, not a screen archive.
The website's waitlist stores submitted contact/consent data in a separate Railway
PostgreSQL database. The native apps do not require or send a waitlist email.
Service-provider retention and a public privacy policy must be reviewed before
public distribution; this implementation guide is not a provider-retention promise.

## Troubleshooting

| Symptom | Check / expected limit |
| --- | --- |
| Only eight digits appear | Internet registration failed; this code works only on a reachable LAN. A new code can retry registration. |
| Same Wi-Fi uses the relay | Guest isolation, VPNs, UDP filtering or blocked reverse TCP ports can prevent LAN reachability. Automatic fallback is intentional. |
| Code fails | Use the current code within five minutes, update both apps, and approve only the intended request. Expired/stopped sessions need a new code. |
| Internet connects nowhere | HTTPS and the configured Railway outgoing TCP endpoint must be reachable. There is no port-443 tunnel or manual port-forwarding setup. |
| Large borders in landscape | Fit preserves the remote aspect ratio; a portrait source cannot fill a landscape screen without cropping. Controls must not reserve permanent space. |
| Phone sees screen but cannot control | Check view-only status, accessibility, focused editable node, full-display sharing and blocked system screens. Do not bypass the permission boundary. |
| Phone session ends after network change | Seamless Wi-Fi/mobile handover and reconnect are not implemented. Start a fresh attended session. |
| Android update is rejected | Another signing key or lower versionCode cannot replace the installed APK. Use the original key; uninstalling loses app data and must not be an automatic fix. |
| Language resets | Update without clearing data; check the same Windows user/Android app installation. Native notification language is currently separate. |
| Old Windows taskbar icon | Check whether an old process or pinned shortcut targets the previous EXE. Test the current portable EXE/window before changing Windows caches. |
| Low FPS or lag | Compare LAN and relay, source refresh rate, motion, codec and bandwidth. Windows JPEG is bandwidth-heavy; see measured limits in PERFORMANCE.md. |

Report reproducible failures using [CONTRIBUTING.md](../CONTRIBUTING.md), with
redacted metadata rather than session codes or private screenshots.
