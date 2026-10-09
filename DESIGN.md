# BetterDesk design system

## Context and approved flow

An accountant in a lit office needs help with a computer or phone and is worried about pressing the wrong button. Connecting occupies the same narrow, quiet column on both devices; a wide desktop does not introduce a dashboard. Both platforms use a black interface, independent of the system theme.

This device's code + Enter device code -> Connect -> other device allows connection -> required platform permission -> connected -> Disconnect.

Technical setup is automatic. This implementation uses native Compose controls and shared Kotlin UI source for Windows and Android.

## Color and typography

Restrained monochrome strategy: near-black background, charcoal controls, off-white text and primary actions, restrained semantic error red. The remote screen itself sits on true black to avoid tinted letterboxing; its colors are not altered. System sans, 30sp primary title, 17sp body, 15sp supporting copy. Shared design tokens live in BetterDeskTheme.

## Layout

Maximum connection column 440dp. 24dp horizontal spacing, 56dp primary action height, rounded 14dp controls, 8/16/24/40dp spacing rhythm. Codes are grouped in fours: twelve digits when available online, eight for local-only fallback. No role choice.

The remote viewer occupies the entire available display, with no app header, footer or reserved toolbar space. Preserve the remote aspect ratio without stretching or cropping; different display shapes can still produce black margins. Both apps enter native full-screen mode when viewing. A small Controls button opens an overlay with Disconnect, input and full-screen actions; Hide closes it. Opening controls does not shrink the image. Respect cutouts and system/keyboard insets for controls. Ending the connection restores normal system bars/window placement.

Use the shared monitor with two connection arrows on the app header, Android launcher/notification, Windows executable and taskbar window. Its source is assets/icon-paths.json; scripts/generate-icons.ps1 builds vector assets and a multi-resolution ICO locally. Android adaptive icons retain the glyph inside the safe area.

## Interaction

All controls have text labels and native focus/disabled states. No modal permissions imitation: genuine OS prompts appear only when needed. Missing platform capabilities remain explicit. No fabricated connection codes, preview sessions, or fake remote screens.

The language button is the exception to visible text labels: show the current English/Portuguese flag, with an accessible label naming the current language and switch action. Store explicit choice through native Android SharedPreferences or Windows user preferences; only first launch uses the system language. Draw flags locally so Windows cannot replace them with letter emoji.

## Local and Internet connections

Both devices expose a short-lived code on opening the app. Entering a code requests that device's screen; the owner approves. Invitation data remains internal, authenticated using the code and transferred without a user-facing link. Clipboard copying only occurs following an explicit button press.

Show “Getting a code…” while registering. A failed Internet registration can still expose a local code with a short local-network-only message. Transport selection is automatic and never adds a role or network setup screen.

Android control permission uses short numbered steps before opening Settings. If Android restricts the APK, the inline recovery path explains the App info overflow menu and opens that app-specific page. Returning checks the actual service permission. Screen sharing without control remains available.

No cloud image-generation assets or remote fonts are required.
