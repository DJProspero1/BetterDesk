# Changelog

This records user-visible milestones. Native artifacts remain local development
builds; dates do not imply a public release. Validation is in
[docs/VERIFICATION.md](docs/VERIFICATION.md).

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
