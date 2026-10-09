# BetterDesk design system

## Context and approved flow

An accountant in a lit office needs help with a computer or phone and is worried about pressing the wrong button. Receiving help occupies the same narrow, quiet column on both devices; a wide desktop does not introduce a dashboard. The app follows the system light/dark preference.

This device's code + Enter device code -> Connect -> other device allows connection -> required platform permission -> connected -> Disconnect.

The preceding conversation established this flow. Technical setup is automatic. This initial implementation uses native Compose controls and shared Kotlin UI source for Windows and Android.

## Color and typography

Restrained strategy: tinted neutral surfaces, deep green action color, restrained semantic error red. High contrast text; no pure black/white surfaces. System sans, 30sp primary title, 17sp body, 15sp supporting copy. Shared design tokens live in BetterDeskTheme.

## Layout

Maximum connection column 440dp. 24dp horizontal spacing, 56dp primary action height, rounded 14dp controls, 8/16/24/40dp spacing rhythm. Codes are grouped in fours: twelve digits when available online, eight for local-only fallback. No role choice. The remote viewer can occupy the remaining window.

## Interaction

All controls have text labels and native focus/disabled states. No modal permissions imitation: genuine OS prompts appear only when needed. Missing platform capabilities remain explicit. No fabricated connection codes, preview sessions, or fake remote screens.

## Local and Internet connections

Both devices expose a short-lived code on opening the app. Entering a code requests that device's screen; the owner approves. Invitation data remains internal, authenticated using the code and transferred without a user-facing link. Clipboard copying only occurs following an explicit button press.

Show “Getting a code…” while registering. A failed Internet registration can still expose a local code with a short local-network-only message. Transport selection is automatic and never adds a role or network setup screen.

Android control permission uses short numbered steps before opening Settings. If Android restricts the APK, the inline recovery path explains the App info overflow menu and opens that app-specific page. Returning checks the actual service permission. Screen sharing without control remains available.

No cloud image-generation assets or remote fonts are required.
