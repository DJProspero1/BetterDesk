# Local-first architecture

## Shared interface

`shared/src/main/kotlin` is compiled into both targets. This uses the JVM common to Android and desktop rather than maintaining two frontends. Compose provides the same layout, labels, state transitions and accessibility semantics. Platform interfaces own capture, input and image decoding.

Both apps use the requested black theme. Explicit English/Portuguese choice is
stored through RemotePlatform.languageTag (Android SharedPreferences and Windows
user preferences); the system language is used only when no choice exists. Flags
are drawn locally rather than relying on emoji support. The monitor/connection
icon comes from assets/icon-paths.json, generated locally into shared Compose and
Android vectors plus SVG/PNG and a multi-size Windows ICO. Windows sets a stable
AppUserModelID and a window icon as well as packaging EXE icons.

VIEWING bypasses the regular app header/footer and enters native full screen.
Android uses WindowInsetsControllerCompat with transient system bars available by
swipe; Windows uses WindowPlacement.Fullscreen and restores the prior placement.
The image fits without cropping. Exit full screen stays visible at the top.
Switch screens is available directly on both devices; input/disconnect controls overlay the image on demand,
with safe-area and IME padding. Android explicitly requests adjustResize so the
keyboard cannot pan the text field outside the visible landscape window.

The implementation is independent of RustDesk; no RustDesk code was copied.

## Connection direction

The connecting device creates an ephemeral TLS listener internally. LAN discovery finds candidate code endpoints; a J-PAKE exchange authenticates the entered code and transfers a pinned TLS invitation in an authenticated encrypted envelope. The requested device approves locally, then connects to the pinned listener. Screen frames flow toward the connecting device and input returns. Both platforms use the same user flow; transport roles are not UI roles.

Opening BetterDesk starts a five-minute code endpoint and UDP discovery responder on port 39471. Only one copy per machine can bind that discovery port. No service runs at boot. Stop/expiry closes listeners. Build/test fixtures use temporary loopback listeners and close them.

It also registers a short-lived lease with `https://api.betterdesk.dev`. The four-digit public routing prefix plus eight locally generated private digits form one twelve-digit code. The app tries LAN pairing first and otherwise allocates two Railway TCP channels: J-PAKE for authenticating and transferring the invitation, then pinned TLS for frames and input. Both devices make outgoing connections, so neither needs router port forwarding. The connecting device remains the TLS server even though its underlying TCP socket is outbound. Android sends this final handshake on the IO dispatcher, as required by the platform.

The relay invitation carries a single-use stream ticket instead of network addresses. HTTPS supplies a validated Railway hostname and port. The relay sees ciphertext and connection metadata. Cancel/decline revokes the pending stream capability; approval polling dismisses a canceled request. Registration failure falls back to a local-only code with a visible explanation. Restarting the singleton relay interrupts sessions; no automatic reconnection or transport migration is implemented.

Local discovery and pairing have a shared 3.5-second attempt budget. Before accepting
the encrypted local invitation, the requested device makes an 800 ms TCP reachability
probe to the authenticated pairing peer's screen port. It uses that observed peer IP
for the session, avoiding unrelated VPN or virtual-adapter addresses. The probe closes
immediately and grants no capture/input permission. If discovery, listener creation,
pairing or the probe fails before approval delivery, a twelve-digit code falls back to
Railway. An eight-digit offline code remains local-only. Once an approval has been
delivered, transport failures end that attempt; they do not create a second request.

## Threading and lifecycle

UI actions use the main dispatcher; decoded images publish through thread-safe StateFlow updates from IO. Blocking sockets use IO threads. One reader owns each socket; writes are serialized. Heartbeats keep the connection alive during platform permission prompts. Screen frames have a four-frame capture queue and an acknowledgement window of four frames locally or twelve through the relay. A full window waits up to 16 ms for an acknowledgement before dropping a frame. Windows capture permits up to 120 updates per second; Android AVC requests 60 FPS. Actual throughput is measured separately. Input has a 128-entry FIFO with generation, epoch and freshness checks. Paste suspends that worker until clipboard readiness, preserving keystroke order while the socket reader continues. Focus release cancels queued work; every stopped session increments the generation. See [keyboard control](KEYBOARD.md).

Desktop Duplication emits new desktop content only. Authenticated heartbeats therefore distinguish an unchanged, healthy capture from a stalled worker, without repeatedly transmitting the same image. A missing capture-worker heartbeat expires input after two seconds. Session counters measure decoded frames and distinct frame IDs submitted for Compose drawing; redraws of the same image do not inflate the result.

The viewer negotiates the codec inside the pinned TLS handshake. Android can produce AVC directly into a MediaCodec surface; Windows decodes it using native FFmpeg. JPEG remains the fallback. AVC uses no B-frames. Any capture-queue or acknowledgement-window drop marks a reference gap and requests a keyframe. Dependent frames are suppressed until a keyframe newer than that gap arrives. Codec headers accompany keyframes. Recovery never grows the video queue, and request frequency is bounded. See [performance](PERFORMANCE.md) and [native dependency notices](../THIRD_PARTY.md).

## Sound and direction changes

Session protocol 4 retains the playback audio negotiation introduced in protocol 2. Native WASAPI
loopback and Android AudioPlaybackCapture produce fixed 20 ms stereo PCM packets;
native output runs away from the socket reader. Separate bounded queues prevent
audio from accumulating indefinitely. Audio and video share the encrypted TCP
stream, so network congestion can still delay both. No microphone is captured.

Either endpoint can request a screen switch. The other endpoint approves locally;
remote input is disabled while the request is pending. Both stop their old media
workers and exchange stopped markers before reversing direction on the same TLS
session. Epochs reject stale media/input. Android obtains fresh capture consent
each time it becomes the source. See [AUDIO_AND_SWITCHING.md](AUDIO_AND_SWITCHING.md).

## Platform differences

- Windows: D3D11/DXGI primary-display capture through JNA, GDI fallback, Skia resize/JPEG encoding, AWT robot mouse input and JNA Unicode keyboard input. Native capture resources are created and released on the capture worker. The D3D library proxy is retained while COM objects reference it. A single .NET Framework EXE embeds the application and JVM, expands them once per build into a content-hashed LocalAppData cache, then opens it. No admin rights or installer. Subsequent runs reuse that cache.
- Android: a connected-device foreground service owns availability while switching apps, with a partial wake lock released on Stop/expiry. Once sharing starts, MediaProjection's foreground service replaces it, avoiding duplicate notifications. Android 13+ requests notification permission before the separate genuine screen-capture prompt. The sharing notification includes Disconnect; service teardown explicitly removes it on local or remote stop. Users can deny notification permission or disable the channel in Android settings. Activity recreation does not stop a connection. Removal from Recents does. Accessibility handles gestures/text; app-only capture cannot authorize full-screen coordinate input.

Platform references: [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [Android restricted settings](https://support.google.com/android/answer/12623953). OS-specific labels can vary by manufacturer.

## Native Android integration policy

SessionExchange adds a separate state flow for files and clipboard, keeping byte
progress updates out of the screen frame model. Its platform boundary covers native
clipboard/file selection/save integration. TransferTransport creates separately
pinned TLS channels, with bounded chunks and integrity checks, using the same LAN
peer or fresh opaque Railway rooms. The original control socket carries only bounded
offers, requests, cancellation and clipboard barriers. See
[files and clipboard](FILES_AND_CLIPBOARD.md) for the complete flow and limits.

Android capabilities belong behind the platform interface while the shared Compose
screens retain the same labels and ordering as Windows. Existing integrations include
MediaProjection, MediaCodec surface encoding, foreground services with actionable
notifications, Activity Result permission requests, system settings intents, and
AccessibilityService input. These use Android's real consent and lifecycle mechanisms.

Prefer additional native APIs when they solve a measured problem. Each integration
must preserve delivered frame rate, input latency, accessibility and connection
reliability, with bounded battery and memory cost. Request permissions when needed,
handle denial, and release resources on failure, cancellation and disconnect. Do not
add broad permissions or keep background resources merely because an API is available.

The current LAN protocol uses UDP broadcast requests and unicast replies; it does not
join a multicast group. No Wi-Fi multicast lock has been added. Android documents
that such a lock enables multicast reception and can increase battery drain. Evaluate
native NSD/mDNS discovery and ConnectivityManager network-change callbacks as separate
improvements, with physical-device coverage and Windows interoperability before
shipping. Neither is implemented in 0.7.0. See [MulticastLock](https://developer.android.com/reference/android/net/wifi/WifiManager.MulticastLock).

## Next milestones

`HelpSession` now uses `SessionTransport` for packet operations and the peer
address required by file exchange. `Wire` implements that interface with the same
TLS/socket framing. Setup sockets remain directly owned until authentication
finishes, so Stop can still cancel setup. There is no transport cutover yet.
Established reads, queued control/input writes, media and heartbeat writes can
observe transport closure in either order. Their I/O failure ends and revokes the
current session; it cannot replace remote Stop with a connection error. Setup and
non-I/O protocol validation failures retain their error behavior. TLS/code
authentication and cryptographic validation remain unchanged.
The [opt-in native WebRTC proof](../experimental/README.md) is excluded from normal
builds and has no production session or permission ownership. Its optional raw
I420 bridge feeds native video tracks in both adapters, with two pending writes
and one latest decoded synthetic sample. It does not attach platform screen
capture, device playback sound, app rendering or remote input to those tracks.
The optional audio/video profile injects bounded 10 ms synthetic PCM into native
audio tracks without hardware microphone capture; decoded callbacks keep only
aggregate peak/rate evidence. Desktop uses a headless audio module and Android
disables hardware AudioRecord before factory creation.

Its shared `RtcControlChannel` now frames the existing control-message schema on
the ordered native data channel, with bounded fragmentation, fixed generation,
consecutive sequence numbers, strict UTF-8/schema parsing and fail-closed cleanup.
Synthetic tests exercise Unicode input, clipboard/barrier and switch messages;
they never execute OS actions. This adapter owns its proof peer, carries no
screen/audio/file bytes and remains separate from production authorization,
freshness, paste execution and transport cutover. Stop and inbound enqueue share
a lock so late callbacks cannot repopulate a revoked inbox.

The [direct Internet connection plan](plans/DIRECT_CONNECTIONS.md) and
[implementation handoff](plans/DIRECT_CONNECTIONS_HANDOFF.md) expand the proposed
media/transport work below. They document future work; the current release still
uses direct LAN or the Railway TCP relay.

1. Prove both directions on physical Windows/Android devices and improve denied-permission recovery and text-entry feedback.
2. Improve mobile desktop control with trackpad mode, zoom and monitor selection.
3. Evaluate a proven hardware-video/transport engine; replace JPEG streaming where justified.
4. Improve Internet transport resilience, adaptive quality, and restrictive-network fallback.
5. Add signed local release packaging and a signed update manifest; the current in-app updater relies on HTTPS to GitHub plus SHA-256 (see RELEASING.md).

The Internet relay is an early implementation with bounded sessions and a single region. Neither OS consent nor firewall policy is bypassed. Native app builds remain local.
