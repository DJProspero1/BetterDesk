# BetterDesk design system

## Context and approved flow

An accountant in a lit office needs help with a computer or phone and is worried about pressing the wrong button. Connecting occupies the same narrow, quiet column on both devices; a wide desktop does not introduce a dashboard. Both platforms use a black interface, independent of the system theme.

This device's code + Enter device code -> Connect -> other device allows connection -> required platform permission -> connected -> Disconnect.

Technical setup is automatic. This implementation uses native Compose controls and shared Kotlin UI source for Windows and Android.

## Color and typography

Restrained monochrome strategy: near-black background, charcoal controls, off-white text and primary actions, restrained semantic error red. The remote screen itself sits on true black to avoid tinted letterboxing; its colors are not altered. System sans, 30sp primary title, 17sp body, 15sp supporting copy. Shared design tokens live in BetterDeskTheme.

## Layout

Maximum connection column 440dp. 24dp horizontal spacing, 56dp primary action height, rounded 14dp controls, 8/16/24/40dp spacing rhythm. Codes are grouped in fours: twelve digits when available online, eight for local-only fallback. No role choice.

The remote viewer occupies the entire available display, with no app header, footer or reserved toolbar space. Preserve the remote aspect ratio without stretching or cropping; different display shapes can still produce black margins. Both apps enter native full-screen mode when viewing. Keep an Exit full screen button visible at the top edge even before Controls opens. Controls and Switch screens remain directly available at the bottom. Controls opens an overlay with Disconnect, input and sound actions; Hide closes it. Opening controls does not shrink the image. Respect cutouts and system/keyboard insets. Ending the connection restores normal system bars/window placement.

Use the shared monitor with two connection arrows on the app header, Android launcher/notification, Windows executable and taskbar window. Its source is assets/icon-paths.json; scripts/generate-icons.ps1 builds vector assets and a multi-resolution ICO locally. Android adaptive icons retain the glyph inside the safe area.

## Interaction

All controls have text labels and native focus/disabled states. No modal permissions imitation: genuine OS prompts appear only when needed. Missing platform capabilities remain explicit. No fabricated connection codes, preview sessions, or fake remote screens.

The language button is the exception to visible text labels: show the current English/Portuguese flag, with an accessible label naming the current language and switch action. Store explicit choice through native Android SharedPreferences or Windows user preferences; only first launch uses the system language. Draw flags locally so Windows cannot replace them with letter emoji.

## Local and Internet connections

Both devices expose a short-lived code on opening the app. Entering a code requests that device's screen; the owner approves. Invitation data remains internal, authenticated using the code and transferred without a user-facing link. Code copying requires an explicit button press. During an approved connection, Windows observes new clipboard copies while Clipboard is on at both ends; Android uses Send clipboard and its Share menu.

Show “Getting a code…” while registering. A failed Internet registration can still expose a local code with a short local-network-only message. Transport selection is automatic and never adds a role or network setup screen.

Android control permission uses short numbered steps before opening Settings. If Android restricts the APK, the inline recovery path explains the App info overflow menu and opens that app-specific page. Returning checks the actual service permission. Screen sharing without control remains available.

No cloud image-generation assets or remote fonts are required.

## Sound, switching and motion

The approval copy explains screen, device sound and control; the microphone stays
off. Unsupported or denied sound is stated briefly without blocking video. A viewer
can mute/unmute playback in Controls.

Switch screens uses the same label on both devices. The requester sees waiting
and Cancel switch; the other device sees an inline request with Allow switch and
Decline, explaining which screen will be shared. The request stays visible even
when Controls was closed. Remote input cannot press this approval. No code-entry
or new role-selection screen appears during the handoff.

Control overlays fade/slide into place in 180 ms and leave in 135 ms, without
moving the remote image or delaying interaction. Native reduced-motion settings
make these transitions immediate. Avoid decorative motion in the stream itself.

## Files and clipboard

Send files is directly available while viewing and sharing. Existing session approval
mentions files and copied text. Clipboard on/off is visible in Controls; Android
adds Send clipboard. Windows also accepts Explorer file drops and native clipboard
paste. No file manager, upload link or separate account is introduced.

Transfer status overlays the remote image above the bottom actions. A single compact
row shows a truncated filename (or file count), a literal phase and Cancel. Receive
offers expose Save files and Decline inline. Active transfers show bytes and rate;
completion lasts six seconds, while a failed transfer remains until addressed.
Transfers expands a bounded, scrollable history. Save completion exposes Open folder.
These states are shared, translated into English and European Portuguese, and
announced politely by screen readers without announcing each byte update.

Use 180 ms fades/short vertical movement and restrained progress interpolation.
Reduced motion removes transitions and the indeterminate animation. Do not animate
the remote image, move it to make room, add sound, or rely on color alone. Android
reuses its current silent foreground notification for coarse transfer progress;
filenames are not placed in notifications and the notification is removed on Stop.
