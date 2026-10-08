# Local-first architecture

## Shared interface

`shared/src/main/kotlin` is compiled into both targets. This uses the JVM common to Android and desktop rather than maintaining two frontends. Compose provides the same layout, labels, state transitions and accessibility semantics. Platform interfaces own capture, input and image decoding.

The initial implementation is deliberately independent of RustDesk. Reusing RustDesk remains an alternative to investigate before committing to an Internet transport/video engine; no RustDesk code was copied.

## Connection direction

The connecting device creates an ephemeral TLS listener internally. LAN discovery finds candidate code endpoints; a J-PAKE exchange authenticates the entered code and transfers a pinned TLS invitation in an authenticated encrypted envelope. The requested device approves locally, then connects to the pinned listener. Screen frames flow toward the connecting device and input returns. Both platforms use the same user flow; transport roles are not UI roles.

Opening BetterDesk starts a five-minute code endpoint and UDP discovery responder on port 39471. Only one copy per machine can bind that discovery port. No service runs at boot. Stop/expiry closes listeners. Build/test fixtures use temporary loopback listeners and close them.

## Threading and lifecycle

UI actions use the main dispatcher; decoded images publish through thread-safe StateFlow updates from IO. Blocking sockets use IO threads. One reader owns each socket; writes are serialized. Heartbeats keep the connection alive during platform permission prompts. Screen frames have a four-frame capture queue and a four-frame acknowledgement window. A full window waits up to 16 ms for an acknowledgement before dropping a frame. Windows capture permits up to 120 updates per second; Android AVC requests 60 FPS. Actual throughput is measured separately. Input has a bounded queue and generation checks. Every stopped session increments the generation to invalidate queued work.

Desktop Duplication emits new desktop content only. Authenticated heartbeats therefore distinguish an unchanged, healthy capture from a stalled worker, without repeatedly transmitting the same image. A missing capture-worker heartbeat expires input after two seconds. Session counters measure decoded frames and distinct frame IDs submitted for Compose drawing; redraws of the same image do not inflate the result.

The viewer negotiates the codec inside the pinned TLS handshake. Android can produce AVC directly into a MediaCodec surface; Windows decodes it using native FFmpeg. JPEG remains the fallback. AVC uses no B-frames. Any capture-queue or acknowledgement-window drop marks a reference gap and requests a keyframe. Dependent frames are suppressed until a keyframe newer than that gap arrives. Codec headers accompany keyframes. Recovery never grows the video queue, and request frequency is bounded. See [performance](PERFORMANCE.md) and [native dependency notices](../THIRD_PARTY.md).

## Platform differences

- Windows: D3D11/DXGI primary-display capture through JNA, GDI fallback, Skia resize/JPEG encoding, AWT robot mouse input and JNA Unicode keyboard input. Native capture resources are created and released on the capture worker. The D3D library proxy is retained while COM objects reference it. A single .NET Framework EXE embeds the application and JVM, expands them once per build into a content-hashed LocalAppData cache, then opens it. No admin rights or installer. Subsequent runs reuse that cache.
- Android: a connected-device foreground service owns availability while switching apps, with a partial wake lock released on Stop/expiry. Once sharing starts, MediaProjection's foreground service replaces it, avoiding duplicate notifications. Android 13+ requests notification permission before the separate genuine screen-capture prompt. The sharing notification includes Disconnect; service teardown explicitly removes it on local or remote stop. Users can deny notification permission or disable the channel in Android settings. Activity recreation does not stop a connection. Removal from Recents does. Accessibility handles gestures/text; app-only capture cannot authorize full-screen coordinate input.

Platform references: [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [Android restricted settings](https://support.google.com/android/answer/12623953). OS-specific labels can vary by manufacturer.

## Next milestones

1. Prove both directions on physical Windows/Android devices and improve denied-permission recovery and text-entry feedback.
2. Improve mobile desktop control with trackpad mode, zoom and monitor selection.
3. Evaluate a proven hardware-video/transport engine; replace JPEG streaming where justified.
4. Design optional locally hosted rendezvous/relay for Internet connections without moving builds to cloud services.
5. Add signed local release packaging and an explicit update trust model.

The public Internet connection promise is not implemented by the local-network prototype. Neither OS consent nor firewall policy is bypassed.
