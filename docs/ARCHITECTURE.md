# Local-first architecture

## Shared interface

`shared/src/main/kotlin` is compiled into both targets. This uses the JVM common to Android and desktop rather than maintaining two frontends. Compose provides the same layout, labels, state transitions and accessibility semantics. Platform interfaces own capture, input and image decoding.

The initial implementation is deliberately independent of RustDesk. Reusing RustDesk remains an alternative to investigate before committing to an Internet transport/video engine; no RustDesk code was copied.

## Connection direction

The **helper** creates an ephemeral TLS listener and invitation. The recipient connects, verifies the pinned certificate, presents the invitation secret, and approves local capture. Screen frames flow from recipient to helper; validated input goes in the reverse direction. Either Windows or Android can take either role.

No background daemon listens at app startup. Listening begins only when the helper explicitly creates an invitation. Build/test fixtures use temporary loopback listeners and close them.

## Threading and lifecycle

The UI/session state uses the main dispatcher. Blocking sockets use IO threads. One reader owns each socket; writes are serialized. Heartbeats keep the connection alive during platform permission prompts. Screen frames have a conflated queue. Input has a bounded queue and generation checks. Every stopped session increments the generation to invalidate queued work.

## Platform differences

- Windows: Java AWT primary-display capture/robot mouse input, JNA Unicode keyboard input, bundled JVM launcher.
- Android: foreground MediaProjection service, system consent, accessibility gestures and editable-node text operations. A service notification has a Stop action. An app-only capture cannot authorize full-screen coordinate input.

## Next milestones

1. Prove both directions on physical Windows/Android devices and improve denied-permission recovery and text-entry feedback.
2. Improve mobile desktop control with trackpad mode, zoom and monitor selection.
3. Evaluate a proven hardware-video/transport engine; replace JPEG streaming where justified.
4. Design optional locally hosted rendezvous/relay for Internet connections without moving builds to cloud services.
5. Add signed local release packaging and an explicit update trust model.

The public Internet connection promise is not implemented by the local-network prototype. Neither OS consent nor firewall policy is bypassed.
