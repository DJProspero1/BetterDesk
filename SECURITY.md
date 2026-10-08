# Security boundaries

This is an early prototype, not audited remote-support software. Report issues privately to the repository owner before public distribution.

- The recipient approves locally before screen capture begins. A transport handshake alone never grants capture or input.
- Each internal invitation uses a new in-memory EC key/certificate and random 256-bit secret. The receiver pins the exact SHA-256 certificate fingerprint. TLS 1.2/1.3 carries the session. A displayed device name is not authentication of a person.
- The eight-digit code is generated with SecureRandom. Local UDP discovery discloses only an endpoint, never the code or an invitation. Bouncy Castle J-PAKE (NIST-3072, SHA-256, all three rounds including mutual confirmation) authenticates knowledge of the code before an AES-256-GCM envelope transfers the invitation. HKDF-SHA256 binds its key to the two participant IDs and protocol version; direction-specific AAD prevents reflection. Each direction carries one envelope.
- Code hosts expire after five minutes or twelve attempts, close on successful pairing, and rate-limit failed attempts. Discovery is unauthenticated and susceptible to local denial of service. Knowing the code permits a connection request, not unattended capture. Exchange codes only with the intended person.
- Invitations expire after five minutes, and the listener closes after its one accepted recipient. No secrets are persisted or logged.
- Only numeric private/loopback IPv4 invitation addresses are accepted in this milestone. No DNS, public IP destinations, arbitrary URL fetches or shell commands.
- The wire format enforces message/frame size and image-dimension limits before decoding. Input actions are explicitly enumerated and checked against a recent frame. Capture is conflated; at most two frames can be in flight until acknowledged by the viewer, avoiding unbounded video latency.
- Stop revokes input first, stops capture and closes sockets. Already dispatched OS input can finish; stop is not a rollback of an action already performed.
- Remote input cannot operate BetterDesk's own permission controls. Android additionally blocks locked devices and common system permission/settings screens.
- This version has no trusted-device/unattended mode. A stored Android accessibility grant does not authorize any future session.

Before production: independent protocol/platform audit, dependency/license review, signed updates, abuse handling, physical-device compatibility testing, denial-of-service testing, and clear release privacy/distribution policies.

Protocol reference: [Bouncy Castle J-PAKE](https://downloads.bouncycastle.org/lts-java/docs/bcprov-lts8on-2.73.5-javadoc/org/bouncycastle/crypto/agreement/jpake/JPAKEParticipant.html). Use of standard primitives is not an audit of BetterDesk's integration.
