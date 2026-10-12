# Changelog

This records user-visible milestones. Native artifacts remain local development
builds; dates do not imply a public release. Validation is in
[docs/VERIFICATION.md](docs/VERIFICATION.md).

## 0.8.0 - Unreleased

- In-app updates on Windows and Android, modeled on T3 Code's GitHub Releases
  updater. The home screen offers a newer version (Update / Later); nothing
  downloads or installs without that choice or during a connection. Downloads
  must match the release's `update.json` size and SHA-256.
- Windows replaces the opened portable EXE and reopens it; the launcher now cleans
  up superseded caches. Android uses its own installer confirmation, after a
  once-only Install unknown apps permission, and refuses a different signing key.
- One version source in `gradle.properties`. This app repository and its Releases
  are public so updates need no credentials; website/infrastructure stay private.
- Versions before 0.8.0 contain no updater: install 0.8.0 manually once.

## 0.7.1 - 2026-10-10

- Physical keyboard input in the shared Windows/Android viewer, including native
  text composition, accents, editing/navigation keys, selection and shortcuts.
- Visible keyboard focus/release; Ctrl+Shift+Space returns to app controls. Type/Send
  stays available for phone software keyboards; drafts no longer use saved instance state.
- Android 13+ native accessibility editor integration with no extra keyboard setup;
  legacy editing restores the cursor and respects Unicode code points/selections.
- Removed the 20 ms input-discard interval. Ordered, bounded typing/paste with
  readiness barriers, cancellation and explicit overflow/failure notices.
- Protocol 4, Android versionCode 9. Update both devices. Tests/audio remain paused;
  local compilation and artifact checks do not verify real keyboard/device behavior.

## 0.7.0 - 2026-10-10

- Session-scoped file and clipboard exchange, with the same Send files, compact
  progress, Save/Decline, Cancel, Retry and Open folder flow on both platforms.
- Windows virtual-file clipboard for Explorer copy/paste, text/images, remote
  Ctrl+C/Ctrl+V/Ctrl+A, and file drops onto BetterDesk.
- Android native multi-file picker, Share menu, scoped Downloads saving and explicit
  Send clipboard. Its existing silent notification reflects transfer progress.
- Separate pinned TLS file connections, chunk acknowledgements, same-session
  partial retry, SHA-256 verification, strict path/size bounds and no silent overwrite.
- Clipboard readiness barrier before remote paste, short reduced-motion-aware
  transitions, persistent failures and bounded transfer details.
- Protocol 3 and Android versionCode 8: update both devices. Initial bounds are
  128 entries/1 GiB per batch and 4 MiB/s relay pacing. No throughput claim.
- Low-resource local packaging and explicit skipped-test metadata. Tests and audio
  checks remain paused; new interactive/native transfer behavior is unverified.

## 0.6.0 - 2026-10-09

- Native device playback sound in both directions, with viewer mute; no microphone capture.
- Switch screens from either app, with the other device's approval and fresh Android capture consent.
- Always-visible full-screen exit and short, reduced-motion-aware control transitions.
- Android remote Home/Back can leave BetterDesk; approval and OS security controls stay protected.
- Safely replace and clean `dist/` after successful local builds. Publish verified
  EXE/APK assets in private GitHub Releases, then remove older versioned releases.
- Session protocol 2: update both devices. Android audio capture needs API 29+
  and permission; apps can block their sound. See the measured performance limits.

## 0.5.0 - 2026-10-09

- Shared black interface and connection/monitor icon across Android and Windows,
  including generated launcher, notification, EXE and window assets.
- Full-screen viewing without permanent app branding. On-demand controls overlay
  the image; Android landscape and keyboard insets preserve usable space.
- Saved English/European Portuguese selection using an accessible flag button.
- Native full-screen/window restoration and stable Windows application identity.
- Contributor, protocol, platform, testing and local-release documentation;
  matching Git-tracked agent instructions with local parity enforcement.

## 0.4.1 - 2026-10-09

- Prefer direct LAN with a bounded attempt budget and authenticated reverse-port
  probe. Fall back to Railway before approval when discovery/pairing is blocked.
- Keep one approval flow and preserve cancellation across route changes.

## 0.4.0 - 2026-10-09

- Integrate native apps with the Railway code broker and encrypted TCP relay.
- Keep local pairing and capture/control approval; add bounded Internet routing,
  cancellation and relay-specific frame acknowledgement capacity.
- Verify both Android capture and Android viewing through the public relay with
  locally built test apps and controlled fixtures.

Earlier development established the shared code-based flow, portable Windows
packaging, Android permission/notification lifecycle, native capture and AVC
decoding. Historical performance experiments are described in
[docs/PERFORMANCE.md](docs/PERFORMANCE.md); they are not new 0.5.0 measurements.
