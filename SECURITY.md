# Security boundaries

This is an early prototype, not audited remote-support software. Report issues privately to the repository owner before public distribution.

- The recipient approves locally before screen capture begins. A transport handshake alone never grants capture or input.
- Each invitation uses a new in-memory EC key/certificate and random 256-bit secret. The receiver pins the exact SHA-256 certificate fingerprint carried in the invitation. TLS 1.2/1.3 carries the session. A trusted invitation-delivery channel is necessary; a display name is not authentication of a person.
- Invitations expire after five minutes, and the listener closes after its one accepted recipient. No secrets are persisted or logged.
- Only numeric private/loopback IPv4 invitation addresses are accepted in this milestone. No DNS, public IP destinations, arbitrary URL fetches or shell commands.
- The wire format enforces message/frame size and image-dimension limits before decoding. Input actions are explicitly enumerated and checked against a recent frame. Frames are conflated rather than queued without bound.
- Stop revokes input first, stops capture and closes sockets. Already dispatched OS input can finish; stop is not a rollback of an action already performed.
- Remote input cannot operate BetterDesk's own permission controls. Android additionally blocks locked devices and common system permission/settings screens.
- This version has no trusted-device/unattended mode. A stored Android accessibility grant does not authorize any future session.

Before production: independent protocol/platform audit, dependency/license review, signed updates, abuse handling, physical-device compatibility testing, denial-of-service testing, and clear release privacy/distribution policies.
