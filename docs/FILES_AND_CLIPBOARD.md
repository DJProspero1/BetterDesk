# Files and clipboard, 0.7.0

This is an early development feature. Both native targets are compiled locally;
interactive Explorer/Android transfers and throughput have **not** been exercised
for this release because the owner paused tests. Compilation is not runtime proof.

## Using it

- Connect normally. Connection approval includes files and copied text.
- Select **Send files** on either device. Windows accepts files and folders;
  Android uses the native document picker, including multiple selected files.
- The other device sees the offer inline and selects **Save files** or **Decline**.
  Sending does not upload to a permanent cloud store.
- Windows can copy files in Explorer and paste on the other computer. Click the
  remote screen before using Ctrl+C, Ctrl+V or Ctrl+A there. BetterDesk exposes
  Windows virtual file descriptors; file bytes are requested on paste. Explorer
  chooses the paste destination and its normal conflict handling applies.
- Dragging files onto the Windows app sends an offer. It does not infer the folder
  underneath a point in the streamed image; the recipient uses Save files.
- Windows shares newly copied text and images while Clipboard is on at both ends.
  Pre-connection clipboard contents are not automatically sent. **Paste** explicitly
  synchronizes current local clipboard content and waits for a matching receipt
  barrier before forwarding the paste action, avoiding stale clipboard pastes.
- Android uses **Send clipboard** while BetterDesk is in the foreground, or the
  native **Share > BetterDesk** action during a connection. Background clipboard
  observation is prohibited by Android; a remote Copy action in another Android
  app does not automatically send that clipboard back to Windows. Use native Share
  or return to BetterDesk and Send clipboard. Unsupported clipboard images can be
  sent/saved as files. Sharing to BetterDesk while disconnected asks you to connect
  first; it does not queue a transfer to an unknown future device.

Save files writes a new batch folder in `Downloads/BetterDesk` on Windows and
Android 10+. Android 8/9 asks for a destination through its native folder picker.
File data is checked before saving. Existing files are never silently replaced;
separate received batches use distinct destination folders. Open folder opens
Explorer or the Android Downloads/document UI. Some document-provider UIs differ.

## Status and recovery

The same compact UI shows waiting, connecting, sending/receiving, checking, saving,
sent/received/saved, canceled and failed states. A small byte/rate indicator appears
while bytes move. Cancel and incoming Save/Decline are visible without opening a
modal. Successful rows fade out after six seconds; Transfers retains recent rows.
Failures remain visible with Retry on the receiving device. The sending device
cannot force the other device to accept a file.

Retry can reuse partial bytes while the same approved connection remains alive.
The source re-hashes the prefix, and the receiver hashes its own stored prefix;
a changed source or corruption fails the final comparison. A failed hash removes
that partial file. Retry after reconnect starts over. A save failure can retry from
verified cache without downloading again. Cancel/Disconnect do not delete files
already saved to the chosen destination or undo an Explorer paste already underway.

Android reuses the existing connection/screen notification, silently, for coarse
progress and incoming offers. Notification permission remains OS-controlled.
The observer stops before foreground teardown so it cannot recreate a stopped
sharing notification. The chosen in-app language is used when creating it.

## Transport and limits

`SessionExchange` owns the shared state and bounded offers. `ExchangePlatform`
owns native selection, clipboard, save and folder opening. `TransferTransport`
owns a separate, short-lived TLS connection for each batch. Files never enter the
screen/audio socket's bulk-data queue. Small manifests and control messages use
the existing authenticated session. Screen direction changes keep the same file
authorization; ending the connection revokes all exchange capabilities.

LAN transfers connect only to the current screen peer's private/loopback address.
They cannot select arbitrary destination hosts. The receiver publishes an ephemeral
TLS pin, one-use secret, port/ticket and 45-second setup expiry through the existing
encrypted session. The sender pins that certificate. The receiver validates the
batch ID and secret before file bytes. Internet transfers allocate an ordinary
opaque Railway stream room; the unused pairing room/device lease is removed.
Each batch gets its own room, so cumulative file bytes do not consume the media
room's 2 GiB limit. No infrastructure deployment or extra public API is needed.

- At most 128 file/folder entries, with safe relative paths, per batch.
- At most 1 GiB per batch and 1 GiB of incoming offers/reservations per connection.
  Reconnect to reset that bound after finishing. Empty-folder-only batches are not
  supported; Android's Downloads provider does not preserve standalone empty folders.
- Four pending incoming and four pending outgoing offers; bounded active jobs.
- Text: 8,000 UTF-8 bytes and the existing 16 KiB control-message envelope.
- Clipboard PNG: 8 MiB encoded; Windows decode rejects dimensions over 4096 or
  8,388,608 pixels before allocating the decoded image.
- 64 KiB chunks, acknowledgements every eight chunks, and per-file SHA-256 checks.
  Receiver offsets are bounded by the offered size. Disk-space preflight reserves
  an extra 16 MiB; saving requires destination space too and can still fail.
- A 4 MiB/s Internet pacing ceiling stays below Railway's existing burst guard.
  Additional pacing reacts to acknowledgements and screen-pressure signals.
  LAN has no fixed bandwidth ceiling. Both still share the physical network;
  this is not a guarantee of 60 FPS or a measured file-transfer speed.

Metadata rejects absolute paths, traversal, backslashes, control/format characters,
Windows reserved names, case-colliding paths, and file/directory conflicts. Only
user-selected or explicitly copied source handles can be requested. Symlinks and
junction-like entries are rejected on Windows. No file is automatically executed.

Private session cache holds incoming partial/verified files until disconnect.
Windows uses `%LOCALAPPDATA%/BetterDesk/transfers`; Android uses app-private cache.
Cleanup waits for transfer/save jobs and does not follow symlinks. A crash or an
Explorer stream that still holds a file open can leave cache for later cleanup;
there is no cross-session resume. Android may cache a user-selected cloud document
whose provider cannot report its size. Saved user destinations are never cleaned
as session cache. No filenames, content, clipboard text or transfer secrets are logged.

Windows virtual clipboard uses OLE IDataObject/IDataObjectAsyncCapability and
FILEGROUPDESCRIPTORW/FILECONTENTS. The clipboard owns native references; callbacks
remain pinned while those references exist. Clearing a session invalidates them.
Ordinary text/images use the platform clipboard. Automatic observation starts from
the current clipboard sequence, avoiding export of old content and feedback loops.

References: [Windows Shell data transfer](https://learn.microsoft.com/en-us/windows/win32/shell/dragdrop),
[Android clipboard restrictions](https://developer.android.com/about/versions/10/privacy/changes#clipboard-data),
[Android document picker](https://developer.android.com/training/data-storage/shared/documents-files).
