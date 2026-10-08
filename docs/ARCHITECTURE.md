# Local-first architecture

## Shared interface

`shared/src/main/kotlin` is compiled into both targets. This uses the JVM common to Android and desktop rather than maintaining two frontends. Compose provides the same layout, labels, state transitions and accessibility semantics. Platform interfaces own capture, input and image decoding.

The initial implementation is deliberately independent of RustDesk. Reusing RustDesk remains an alternative to investigate before committing to an Internet transport/video engine; no RustDesk code was copied.

## Connection direction

The connecting device creates an ephemeral TLS listener internally. LAN discovery finds candidate code endpoints; a J-PAKE exchange authenticates the entered code and transfers a pinned TLS invitation in an authenticated encrypted envelope. The requested device approves locally, then connects to the pinned listener. Screen frames flow toward the connecting device and input returns. Both platforms use the same user flow; transport roles are not UI roles.

Opening BetterDesk starts a five-minute code endpoint and UDP discovery responder on port 39471. Only one copy per machine can bind that discovery port. No service runs at boot. Stop/expiry closes listeners. Build/test fixtures use temporary loopback listeners and close them.

## Threading and lifecycle

The UI/session state uses the main dispatcher. Blocking sockets use IO threads. One reader owns each socket; writes are serialized. Heartbeats keep the connection alive during platform permission prompts. Screen frames have a conflated queue and a two-frame acknowledgement window. Capture targets 30 FPS with reused encoding buffers. Input has a bounded queue and generation checks. Every stopped session increments the generation to invalidate queued work.

## Platform differences

- Windows: Java AWT primary-display capture/robot mouse input, JNA Unicode keyboard input, bundled JVM. A single .NET Framework EXE embeds the application, expands it once per build into a content-hashed LocalAppData cache, then opens it. No admin rights or installer. Subsequent runs reuse that cache.
- Android: separate connected-device foreground service owns availability while switching apps, with a partial wake lock released on Stop/expiry. Activity recreation does not stop a connection. Removal from Recents does. MediaProjection has its own foreground service and genuine system consent. Accessibility handles gestures/text; app-only capture cannot authorize full-screen coordinate input.

Platform references: [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [Android restricted settings](https://support.google.com/android/answer/12623953). OS-specific labels can vary by manufacturer.

## Next milestones

1. Prove both directions on physical Windows/Android devices and improve denied-permission recovery and text-entry feedback.
2. Improve mobile desktop control with trackpad mode, zoom and monitor selection.
3. Evaluate a proven hardware-video/transport engine; replace JPEG streaming where justified.
4. Design optional locally hosted rendezvous/relay for Internet connections without moving builds to cloud services.
5. Add signed local release packaging and an explicit update trust model.

The public Internet connection promise is not implemented by the local-network prototype. Neither OS consent nor firewall policy is bypassed.
