# Keyboard control

Version 0.7.1 uses the same keyboard receiver on Windows and Android. Update both
devices: keyboard packets require session protocol 4. Runtime tests remain paused
at the owner's request; the implementation and bounds below are not device-test
results. See [verification](VERIFICATION.md).

## Using it

Click/tap the remote field, then type on your physical keyboard. This also works
with a physical keyboard attached to the Android viewer. The compact **Keyboard
on · Release** control identifies remote focus. Click Release or press
**Ctrl+Shift+Space** to return to BetterDesk's controls. Clicking a local control
or leaving the app releases remote typing. No global keyboard listener is used.

On a phone without a physical keyboard, **Controls → Type** opens the existing
native text field; **Send** inserts the text remotely. Enter, Delete (backspace)
and Tab are also available there. Tapping the remote view does not automatically
open the local software keyboard. Local connection-code fields and Type drafts
never forward hardware shortcuts to the remote device.

The viewer uses Compose's native text input, allowing the local keyboard layout,
accents, AltGr characters and IME composition to produce Unicode text. Uncommitted
composition is shown briefly beside keyboard status, then sent once committed.
Drafts/composition are held only in memory, not saveable state or logs. Releasing
focus discards unfinished composition. The remote image remains the editor.

Editing keys include Enter, Backspace, Delete, Tab/Shift+Tab, arrows, Home/End,
Page Up/Down, Insert and F1–F12. Ctrl/Alt letter and number chords are passed as
atomic key presses; Shift selection is preserved. Ctrl+C/X/A, Ctrl+V,
Ctrl+Insert, Shift+Insert and Shift+Delete use the clipboard/editing path.
Ctrl+Shift+V waits for clipboard readiness and requests plain paste on Windows.
Android paste uses its native context action; rich/plain behavior belongs to its
editor. Windows-key/Meta combinations and OS-reserved shortcuts remain local.
Ctrl+Alt character chords stay with the local layout because they may be AltGr.
There is no held-key/game controller mode; every transmitted chord releases its
modifiers and key within the same native call.

## Native receivers

| Receiver | Implementation and limits |
| --- | --- |
| Windows | Unicode SendInput for text; AWT/native key presses for editing/navigation/chords. Permission controls in BetterDesk remain blocked; no UAC/secure-desktop bypass or elevation. Shortcuts depend on the focused application. |
| Android 13+ | The existing accessibility service enables FLAG_INPUT_METHOD_EDITOR. A matching foreground editor receives commitText, context actions, editor actions and key-down/up pairs through AccessibilityInputConnection. No separate keyboard installation or default-keyboard change. |
| Android 8–12 or no native editor | Focused accessibility node editing, with explicit caret restoration; backspace/delete and movement respect Unicode code points and selected ranges. Arrow/Home/End selection and Ctrl word movement use the exposed text. Tab uses native focus search. Enter uses ACTION_IME_ENTER where available, otherwise a newline in multiline fields. |

Android's editor package must match the refreshed active window. Locked devices,
BetterDesk approval controls, common settings/permission/system screens remain
blocked. Android Home/Back can still leave BetterDesk during an approved session.
The legacy fallback rejects password/masked fields and unknown selections rather
than replacing text incorrectly. Native Android 13+ can commit text without
reading a password; password Copy/Cut actions are refused.

Custom renderers, games, embedded editors and OEM implementations may expose no
usable editor or reject actions. The fallback cannot provide arbitrary Ctrl/Alt
shortcuts, undo/redo, function keys or page navigation. Native API submission is
asynchronous and has no execution acknowledgement; a receiver reports detectable
rejection with a short **This field or key is unavailable** notice, but cannot
detect every application-level rejection. Accessibility actions can race an OS
focus change after submission. Code-point deletion does not promise whole grapheme
cluster deletion for every combined emoji on the legacy fallback.

## Ordering and bounds

- One bounded 128-entry outgoing FIFO orders pointer, text, keys and paste.
  Paste waits up to 15 seconds for the existing clipboard acknowledgement before
  forwarding its keystroke; later inputs cannot overtake it. Paste failure clears
  pending input and reports a notice. Other queued input expires after two seconds.
- Queue overflow clears pending input with a visible paused notice. Focus release,
  stop and direction changes invalidate pending commands; every send rechecks
  current authorization, screen freshness, session generation and direction epoch.
- The former 20 ms discard interval is removed. A receiver accepts normal bursts
  in order and terminates an excessive stream above 256 valid inputs per second,
  rather than silently losing characters. Native application handling remains
  subject to OS timing; Android gesture completion and editor focus are asynchronous.
- Text packets contain at most 512 UTF-16 units. Committed batches are bounded to
  8192 units and split without dividing surrogate pairs. Invalid Unicode/control
  text is rejected. Native line endings are normalized; keys/modifiers are strict
  symbolic allowlists, never sender-supplied OS key codes or shell commands.
- Android fallback edits are capped at 262144 UTF-16 units. No editor contents or
  key history are logged, stored or transmitted back as part of keyboard handling.

## Deferred runtime coverage

Once tests are explicitly resumed, check Windows→Android (13+ and an older OS),
Windows→Windows, Android physical keyboard→both targets, and Android Type/Send→both.
Use synthetic text for rapid typing, Portuguese accents/AltGr, emoji, IME commits,
multiline input, selected replacement, Delete/Backspace, Shift selection, Tab and
shortcuts. Include delayed/failed paste followed by typing, app/window focus loss,
view-only mode, stalled frames, disconnect/switch races and protected screens.
Confirm no stuck modifiers, duplicate characters or local-field forwarding. Test
real applications and OEM devices; compilation cannot establish these outcomes.

API references: [Android accessibility input connection](https://developer.android.com/reference/android/accessibilityservice/InputMethod.AccessibilityInputConnection),
[Compose keyboard handling](https://developer.android.com/develop/ui/compose/touch-input/keyboard-input/commands).
