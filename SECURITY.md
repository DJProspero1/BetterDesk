# Security boundaries

This is an early prototype, not audited remote-support software. Report issues privately to the repository owner before public distribution. The implementation is described in [the protocol guide](docs/PROTOCOL.md); this document states its trust boundaries, not a guarantee that every attack has been excluded.

- The recipient approves locally before screen capture begins. A transport handshake alone never grants capture or input.
- Each internal invitation uses a new in-memory EC key/certificate and random 256-bit secret. The receiver pins the exact SHA-256 certificate fingerprint. TLS 1.2/1.3 carries the session. A displayed device name is not authentication of a person.
- Eight private digits are generated with SecureRandom. An Internet code prepends a four-digit routing prefix assigned by the relay. The private digits are never sent to the broker. Local UDP discovery discloses only an endpoint, never the code or an invitation. Bouncy Castle J-PAKE (NIST-3072, SHA-256, all three rounds including mutual confirmation) authenticates knowledge of the private digits before an AES-256-GCM envelope transfers the invitation. HKDF-SHA256 binds its key to the two participant IDs and protocol version; direction-specific AAD prevents reflection. Each direction carries one envelope.
- Code hosts expire after five minutes or twelve attempts, close on successful pairing, and rate-limit failed attempts. Discovery is unauthenticated and susceptible to local denial of service. Knowing the code permits a connection request, not unattended capture. Exchange codes only with the intended person.
- After J-PAKE authentication, the local receiver probes the screen port on the actual pairing peer's address, with an 800 ms timeout. It neither probes advertised third-party hosts nor begins capture. A failed probe leaves the code usable for relay pairing. Cancellation or a completed local approval never triggers an automatic second request through the relay.
- Invitations expire after five minutes, and the listener closes after its one accepted recipient. No secrets are persisted or logged.
- Local invitations accept only numeric private/loopback IPv4 addresses. Relay invitations contain no host or port and are accepted only through authenticated code pairing, never an external link. The app obtains relay routing from `https://api.betterdesk.dev` with system HTTPS trust, no redirects, and a strict Railway TCP hostname check. No arbitrary URL fetches or shell commands.
- The relay transports J-PAKE and pinned TLS bytes and cannot decrypt screen/input content. It observes connection metadata, timing and traffic volume. Single-use TCP tickets are routing capabilities sent before TLS, so a hostile network can consume or disrupt them; they do not replace end-to-end authentication. No privileged server credentials are bundled in either app.
- The wire format enforces message/frame size and image-dimension limits before decoding. Input actions are explicitly enumerated and checked against a recent frame. Capture is conflated; at most four frames locally or twelve through the relay can be in flight until acknowledged by the viewer. Increased Internet capacity allows round-trip acknowledgements without an unbounded queue.
- Stop revokes input first, stops capture and closes sockets. Already dispatched OS input can finish; stop is not a rollback of an action already performed.
- Pending relay requests are explicitly canceled with a private capability. The requested device checks that capability while waiting for approval. Failed cleanup cannot silently authorize capture; tickets, local approval and invitation expiry still apply. Service restarts and broken connections require a fresh attended session.
- Remote input cannot operate BetterDesk's own permission controls. Android additionally blocks locked devices and common system permission/settings screens.
- Android Home/Back can leave BetterDesk during an authorized session. This does
  not permit taps, text or Enter on its consent/switch controls.
- Switching direction requires one local request and the other device's explicit
  approval over the existing pinned TLS connection. Both media writers stop before
  the new direction starts; a new epoch rejects old frames, sound, acknowledgements
  and input. Capture permissions are not transferred between devices. A declined
  request preserves the current stream; a failure during handoff closes access.
- Playback sound capture is scoped to approved screen sharing. Android uses
  AudioPlaybackCapture with the genuine audio/MediaProjection grants; Windows uses
  a render-endpoint WASAPI loopback stream. Neither opens the microphone. No audio
  is recorded to disk or included in logs. Audio packet size and queues are bounded.
- This version has no trusted-device/unattended mode. A stored Android accessibility grant does not authorize any future session.
- Session protocol 4 retains approved file/clipboard exchange. Only new Windows
  clipboard content is observed automatically, while both devices enable it;
  Android uses explicit foreground actions. File manifests identify user-selected
  handles, never arbitrary remote paths. A paste barrier acknowledges the expected
  clipboard revision before forwarding paste input.
- Keyboard text and symbolic keys use bounded ordered input, explicit modifier
  allowlists and fresh-frame/session/epoch checks. A 256-input/second ceiling
  fails excessive streams instead of discarding normal bursts. Paste blocks later
  keystrokes until readiness; timeout/overflow clears pending input with a notice.
  Focus release cancels pending typing. Chords never leave remote keys held.
  Android 13+ uses only an accessibility input connection matching the refreshed
  foreground package; existing protected-screen checks still apply. Older Android
  rejects password/unknown-selection rewrites. Native submission is asynchronous,
  so OS focus races remain possible; see [keyboard control](docs/KEYBOARD.md).
- Bulk transfers use a different ephemeral pinned TLS connection, authenticated
  with a secret sent inside the approved screen connection. LAN destinations are
  restricted to the existing peer; Railway routing still comes from validated
  configuration. Paths, entry counts, bytes, images and pending offers are bounded.
  SHA-256 checks gate completion. Disconnect closes file sockets and invalidates
  native clipboard capabilities; no automatic file execution or silent overwrite.
  Private transfer cache can contain file bytes until teardown; interrupted cleanup
  or open Explorer handles can leave remnants. See [files and clipboard](docs/FILES_AND_CLIPBOARD.md).
- The new OLE/Android file paths have not undergone runtime/security testing in
  0.7.0 or 0.7.1 because tests were paused by the owner. The 0.7.1 keyboard paths
  likewise remain runtime unverified. Compilation is not an assurance of
  correct shell interoperation, cancellation, throughput or adversarial robustness.

Before production: independent protocol/platform audit, dependency/license review, signed updates, abuse handling, physical-device compatibility testing, denial-of-service testing, and clear release privacy/distribution policies.

## Reporting a vulnerability

While the repository is private, contact its owner through the established private
project channel. Do not place exploit details, live codes, tickets, credentials,
screen captures or typed private data in an ordinary issue or public discussion.
This repository does not yet advertise a dedicated security email, confirmed
GitHub private-reporting endpoint or response-time commitment. Establish and test
that channel before opening the project publicly; do not invent a contact address.

A useful private report includes affected source revision/app versions, both OS
versions, LAN or relay route, prerequisites, a minimal reproduction using synthetic
data, impact and any suggested fix. Report capture without approval, input after
stop, replay, pin/code bypass and permission-boundary failures as security issues.
Never test against someone else's device without their authorization.

Only the current development line is maintained; there is no supported-version
matrix or automatic signed updater yet. Fixed builds require a local rebuild and
an attended update. See [local release preparation](docs/RELEASING.md) and
[platform data handling](docs/PLATFORMS.md) for remaining delivery/privacy work.

Protocol reference: [Bouncy Castle J-PAKE](https://downloads.bouncycastle.org/lts-java/docs/bcprov-lts8on-2.73.5-javadoc/org/bouncycastle/crypto/agreement/jpake/JPAKEParticipant.html). Use of standard primitives is not an audit of BetterDesk's integration.
