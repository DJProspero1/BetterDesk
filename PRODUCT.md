# BetterDesk

## Register

product

## Users

Ordinary people, including accountants, connecting Windows computers and Android phones while present. They should not need to understand remote access or choose a helper/recipient role.

## Product Purpose

Open-source attended remote access in both directions between Windows and Android. Both devices show their own short code and a field to enter another device's code. The device whose screen is requested approves the connection. The user's October 8 feedback explicitly replaces the earlier invitation-link and helper-role flow.

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

## Accessibility & Inclusion

Keyboard focus, screen-reader labels, large touch targets, scalable text, no status communicated by color alone, and no decorative motion. Match the system theme. English and European Portuguese copy share the same flow.

## Local development

The user authorizes private GitHub source hosting and the BetterDesk Railway relay. All native builds and tests run locally. No cloud builds or GitHub Actions. Connections prefer a reachable local network and otherwise use the public relay. App binaries stay local; the separate website offers a waitlist, not downloads.
