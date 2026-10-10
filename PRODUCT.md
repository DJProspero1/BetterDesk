# BetterDesk

## Register

product

## Users

Ordinary people, including accountants, connecting Windows computers and Android phones while present. They should not need to understand remote access or choose a helper/recipient role.

## Product Purpose

Attended remote access in both directions between Windows and Android, developed with a possible open-source release in mind. Both devices show their own short code and a field to enter another device's code. The device whose screen is requested approves the connection. The product uses codes and a shared flow without invitation links or helper-role selection.

## Brand Personality

Simple, calm, familiar. Short literal labels, generous readable controls, no technical vocabulary in the receiving flow. Name confirmed by the user: BetterDesk.

## Anti-references

No busy remote administration dashboard, account gate, setup wizard with five permissions at once, advertising, decorative metrics, or different mobile and desktop navigation.

## Design Principles

1. One identical home screen: this device's code, another device's code, Connect. No helper roles or invitation links in the UI.
2. Same components, words, states, and ordering on Windows and Android.
3. Automate configuration, never fabricate consent or connection success.
4. Clearly show the connected device, viewing versus controlling, and Disconnect.
5. A stopped connection/code cannot grant future access. Switching apps should preserve an active or pending connection.
6. Responsive interaction is essential. Bound frame queues, measure delivered performance, and never call a target FPS an achieved result.
7. Windows delivery is a single portable EXE; Android updates remain local APKs. Explain unavoidable Android restrictions inside the app.
8. Use native Android integrations wherever they improve the experience without reducing quality. Preserve the shared Windows/Android flow. Prefer built-in system capabilities for capture, input, permissions, notifications and lifecycle; measure performance and battery impact before adding background work.
9. Apply design feedback to both apps by default. Use a black interface and a shared computer/connection icon. Viewing is full screen with controls available on demand; permanent app branding must not take space from the remote screen.
10. Save an explicit language choice across rotation, app switches, restarts and updates. Use a flag button with an accessible language name, not a platform-dependent flag emoji.
11. Keep Exit full screen directly visible. Either device can request Switch screens, with clear approval on the other device and fresh OS capture permission where required.
12. Share supported device sound alongside the screen, with a viewer mute control. Never capture the microphone. Use short, purposeful motion that respects reduced-motion settings.
13. Exchange files and clipboard content within the approved connection. Use familiar Windows copy/paste and Android Share/file-picker integrations, with the same Send files action and compact status. Transfer progress must not resize the screen or interrupt control. Cancellation, retry, completion and errors remain clear; no silent replacement of existing files.
14. A physical keyboard types into the focused remote field from either viewer. Use native layouts/composition, preserve input order and provide a visible way to release keyboard focus. Local app fields remain local. Native OS limits are explicit; never silently claim a rejected key worked.

## Accessibility & Inclusion

Keyboard focus, screen-reader labels, large touch targets, scalable text, no status communicated by color alone, and no decorative motion. Use the requested black theme with high-contrast neutral text. English and European Portuguese copy share the same flow.

## Local development

The user authorizes private GitHub source hosting, private GitHub Releases and the BetterDesk Railway relay. All native builds and tests run locally. No cloud builds or GitHub Actions. Connections prefer a reachable local network and otherwise use the public relay. Keep only the latest verified distribution and private app release, replacing older outputs only after verification. The separate website offers a waitlist, not downloads.
