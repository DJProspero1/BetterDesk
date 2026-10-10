# Direct connections: agent handoff and work checklist

Last updated: 10 October 2026. State: **documentation-only draft PR; implementation
not started**. Read the [proposed design](DIRECT_CONNECTIONS.md) before coding.

## Resume here

The owner wants direct connections between different networks, with automatic
relay fallback and the same simple Windows/Android flow. They asked for this draft
PR so the feature can be developed later. This handoff does not claim that P2P is
implemented, measured, deployed or ready to ship.

1. Read [AGENTS.md](../../AGENTS.md), [PRODUCT.md](../../PRODUCT.md),
   [DESIGN.md](../../DESIGN.md) and [SECURITY.md](../../SECURITY.md).
2. Inspect the draft PR, current branch/diff and any later comments. Fetch remote
   state before continuing; do not reset or overwrite another person's work.
3. Recheck baseline claims against source. This plan started from app commit
   `87f57b7b220eac3604a3b25dd35b1c16652d2491`, release 0.7.1, protocol 4. The
   sibling infrastructure source inspected was
   `6a2f50c00a531900976687ea049a5632f0fbff6b`; no infrastructure change is in this PR.
4. Begin **M0** below: resolve the native integration/build route and record the
   decision with evidence. Do not start by replacing HelpSession networking.
5. Update this checklist, decisions and coverage after each meaningful change.
   Mark completed work only when its stated exit evidence exists. Keep the PR a
   draft until the owner resumes implementation and the appropriate checks pass.

Suggested continuation prompt:

> Continue the direct Internet connection feature from this PR. Read
> docs/plans/DIRECT_CONNECTIONS_HANDOFF.md and the linked design, inspect current
> work, then take the first incomplete milestone. Improve the plan where evidence
> warrants it and update its decisions and status. Preserve the shared Windows /
> Android flow, local-build policy and active pause on tests and audio workloads.
> Report implemented, statically checked and still-unverified work separately.

### Active operational constraints

- Tests, emulators, app launches, audio fixtures, benchmarks and live smoke checks
  are **paused at the owner's request**, across subsequent tasks. The request to
  prepare this PR does not resume them. Do not schedule them or run them quietly.
- No development servers without an explicit request. No deployment, provider
  provisioning, DNS change, release, app version bump or dependency addition was
  made for this documentation handoff.
- Native builds remain on the owner's local machine. When later code needs a
  build while tests remain paused, use the existing bounded command:

  ```powershell
  .\scripts\build-local.ps1 -SkipTests -LowResource
  ```

  That command packages artifacts and cleans dist safely; it is not needed for
  documentation changes. Existing native build settings use one Gradle worker
  and a 1536 MiB heap. A future WebRTC source build needs its own bounded resource
  plan and must not silently launch a large parallel build.
- Keep source/private releases private, website waitlist-only, Windows portable
  EXE, Android package/signing continuity, and all three agent files identical.
- The app, infrastructure and website are separate repositories. Infrastructure
  main auto-deploys: use a separate branch/PR there if server work becomes needed.
  Do not bundle an unreviewed production deployment with a client implementation.
- This plan may be improved. Preserve the user's requirements and security
  boundaries; record why a design decision changed and the evidence supporting it.

## Code map

| Existing source | Why the next agent needs it |
| --- | --- |
| [HelpSession.kt](../../shared/src/main/kotlin/app/betterdesk/core/HelpSession.kt) | Concrete socket/Wire ownership, hello/accepted protocol 4, startMedia/pump, input queues, requestSwitch/stopForSwitch and stopTransport. Separate session lifetime from optional signaling socket lifetime. |
| [DeviceAvailability.kt](../../shared/src/main/kotlin/app/betterdesk/core/DeviceAvailability.kt) | One displayed code, LAN/relay availability, single-delivery guard, poll/cancel/close. Preserve one approval request. |
| [Pairing.kt](../../shared/src/main/kotlin/app/betterdesk/core/Pairing.kt) | LAN discovery, J-PAKE CodeExchange, transcript-derived encryption and single-use envelopes. Not a reusable signaling mailbox. |
| [Invitation.kt](../../shared/src/main/kotlin/app/betterdesk/core/Invitation.kt) / [TlsIdentity.kt](../../shared/src/main/kotlin/app/betterdesk/core/TlsIdentity.kt) | Expiry, validated local/relay endpoints, ephemeral identity and certificate pinning. New transport must preserve authenticated identity. |
| [RelayClient.kt](../../shared/src/main/kotlin/app/betterdesk/core/RelayClient.kt) | API v1 config, leases, room allocation/cancellation, strict Railway host validation and blocking TCP connection. |
| [Wire.kt](../../shared/src/main/kotlin/app/betterdesk/core/Wire.kt) | Strict Message schema, bounded frame/audio packets, input allowlist; not a UDP or WebRTC framing implementation. |
| [RemotePlatform.kt](../../shared/src/main/kotlin/app/betterdesk/core/RemotePlatform.kt) | Native capture/input/decode/audio boundary. Adapt media flow without duplicating permission ownership. |
| [FrameWindow.kt](../../shared/src/main/kotlin/app/betterdesk/core/FrameWindow.kt) / [FramePerformance.kt](../../shared/src/main/kotlin/app/betterdesk/core/FramePerformance.kt) / [VideoRecovery.kt](../../shared/src/main/kotlin/app/betterdesk/core/VideoRecovery.kt) | Existing backpressure, distinct-frame metrics and AVC recovery. Do not blindly layer TCP frame-ack pacing onto RTP. |
| [DesktopPlatform.kt](../../desktop/src/main/kotlin/app/betterdesk/desktop/DesktopPlatform.kt) / [DxgiCapture.kt](../../desktop/src/main/kotlin/app/betterdesk/desktop/DxgiCapture.kt) / [WasapiAudio.kt](../../desktop/src/main/kotlin/app/betterdesk/desktop/WasapiAudio.kt) | Windows capture produces JPEG today; hardware encode/video-track integration and playback-only audio need explicit ownership. |
| [ScreenShareService.kt](../../android/src/main/kotlin/app/betterdesk/ScreenShareService.kt) / [SurfaceEncoder.kt](../../android/src/main/kotlin/app/betterdesk/SurfaceEncoder.kt) / [AndroidPlatform.kt](../../android/src/main/kotlin/app/betterdesk/AndroidPlatform.kt) / [PlaybackAudio.kt](../../android/src/main/kotlin/app/betterdesk/PlaybackAudio.kt) | Android projection, service lifecycle, AVC encoder, current JPEG decode and playback capture/output. New Windows video codecs need a matching receiver. |
| [SessionExchange.kt](../../shared/src/main/kotlin/app/betterdesk/core/SessionExchange.kt) / [TransferTransport.kt](../../shared/src/main/kotlin/app/betterdesk/core/TransferTransport.kt) / [FileExchange.kt](../../shared/src/main/kotlin/app/betterdesk/core/FileExchange.kt) | File authorization, metadata, clipboard barriers, TLS transfer and integrity. Route changes must preserve these semantics. |
| [RemoteKeyboard.kt](../../shared/src/main/kotlin/app/betterdesk/ui/RemoteKeyboard.kt) / [BetterDeskApp.kt](../../shared/src/main/kotlin/app/betterdesk/ui/BetterDeskApp.kt) | Shared focus, typing, notices and session UI. Preserve keyboard and reduced-motion behavior. |

Server work belongs in [DJProspero1/BetterDesk-Infrastructure](https://github.com/DJProspero1/BetterDesk-Infrastructure):
`src/server.mjs` owns `/v1/config`, leases, requests/status/cancel and TCP rooms;
`src/main.mjs` starts the service; `.railway/railway.ts` manages configuration;
`test/server.test.mjs` and `scripts/broker-fixture.mjs` provide later validation
seams. Read its own AGENTS.md before editing. No server endpoint proposed here
exists merely because it is described in the plan.

## Milestones and exit evidence

| ID | Status | Deliverable | Exit evidence |
| --- | --- | --- | --- |
| M0 | Not started | Native library/binding decision and local build feasibility | Exact source/version/artifact provenance, target/ABI list, licenses/notices, resource plan, codec/capture bridge and dependency-size estimate for Windows and Android. A locally compiling adapter when implementation resumes; runtime still unverified while paused. |
| M1 | Not started | Extract transport boundary, retaining current TLS behavior | Small reversible refactor covering session vs transport lifetime; approval/Stop/epoch/input semantics unchanged. Relevant checks after test pause is lifted. |
| M2 | Not started | Authenticated WebRTC proof behind a development-only opt-in | Both adapters, fingerprint binding and bounded synthetic data-channel exchange over existing TLS bootstrap; no fake production route label. Runtime proof requires explicit test resumption. |
| M3 | Not started | Screen and playback audio tracks in both directions | Capture/encode/decode/render ownership documented; no microphone, double capture or unbounded media queues. Real device frame-age/quality/CPU evidence when allowed. |
| M4 | Not started | Direct control/clipboard/files and safe route cutover | Direct payload in both directions, ordered input/paste, verified files, transfer backpressure, old-path rejection and deterministic Stop. No claim of all-direct while file bytes still relay. |
| M5 | Not started | HTTPS signaling and deployable TURN fallback in sibling infra PR | Reviewed authentication/schema, bounded endpoints, scoped credentials, selected provider, external UDP/TCP/TLS reachability, rollback and live validation when authorized. Current Railway TCP bootstrap is not sufficient. |
| M6 | Not started | Acceptance matrix, default enablement and local release | Separate-network direct proof, forced fallback, security/lifecycle regression evidence, updated guides/notices, local EXE/APK/checksums and verified private release only after applicable checks are permitted and pass. |

M0 precedes SDK-specific work; M1 precedes transport integration; M2 precedes M3
and M4. M5's design can proceed alongside client work, but **M6 requires all of
M3/M4/M5**. These are task boundaries, not authorization to spawn agents or create
additional user-owned chats. Keep an implementation matrix in this file as code
lands; do not check a milestone off for only writing its plan.

## First implementation decisions to record

| Decision | Starting position | Still needed |
| --- | --- | --- |
| Engine/bindings | Prefer maintained native WebRTC | Pinned candidate comparison, local build/packaging feasibility on both targets |
| Initial bootstrap | Existing pinned TLS for isolated proof | Explicit feature capability/version strategy; proof limitations documented |
| Full signaling | Authenticated, end-to-end protected messages over HTTPS | API version, mailbox/WS choice, crypto/transcript review, size/rate/TTL limits |
| Relay | Preserve legacy fallback during migration; evaluate TURN | Provider/public UDP support, costs, credential issuance/rotation and abuse controls |
| Capture bridge | Preserve platform permission/service ownership | Codec/surface bridge, Windows hardware encoder and Android receive path |
| Cutover | Explicit transport-generation commit inside one approved session | Exact failure/rollback state machine; file and input ordering policy |
| Old clients | No silent schema extensions or downgrade | Deliberate compatibility behavior or clear update requirement |

## Acceptance matrix, queued until tests are explicitly resumed

Record actual OS versions, native dependency revision, network conditions, codec,
resolution, build hashes and measured results. Never record codes, credentials,
raw SDP, public/private IPs, real screen content or typed text. Mark unavailable
physical hardware/network cases as untested, not passed.

| Area | Required cases and pass condition |
| --- | --- |
| Direction/platform | Windows→Android, Android→Windows, Windows→Windows; exercise the same shared behavior for Android→Android when devices are available. Correct source permissions and viewer input on each. |
| Local/offline | Same reachable LAN remains direct, including offline eight-digit code. Guest isolation/VPN/multiple adapters fall back without duplicate approval. |
| Internet direct | Two genuinely different external networks; inspect selected non-relay candidate pair and actual payload route. Local Wi-Fi/emulator loopback is insufficient. Include IPv4 and IPv6 where available. |
| Fallback | Force UDP failure, hard NAT and direct timeout; prove TURN UDP and TCP/TLS paths as supported. Confirm all-direct vs relayed reporting and no content before approval. |
| Restricted bootstrap | Block the legacy Railway TCP port; HTTPS signaling can still establish a supported direct/TURN session. Enterprise proxy limitations are documented. |
| Authentication | Wrong code, expired attempt, forged fingerprint/candidate, duplicate/reordered signaling and downgrade attempts fail closed; credentials cannot be used as an open relay. |
| Lifecycle | Decline/Stop at each setup/cutover phase, source lock/revoked permission, rotation/backgrounding, network changes and lost signaling. No capture/input after revocation or resurrection of an ended session. |
| Direction switch | One local approval, new source capture consent, epoch separation, no old media/input, no duplicate native encoder/service. |
| Keyboard/clipboard | Fast Unicode typing, shortcuts, Ctrl+V followed by typing, pending paste at cutover, custom/protected Android fields. No reordering, duplicate execution or stuck modifiers. |
| Files | Hash matches, consent/cancel/retry, disk failure, no silent overwrite; large transfers do not starve input/media. Interrupted cutover never silently duplicates writes. |
| Media/performance | Compare old relay, direct and TURN using identical device/codec/resolution/workload. Record input RTT, frame age, distinct drawn FPS/gaps, loss, bitrate, CPU/memory/battery and cold setup time; no universal 60 FPS claim. |
| Sound | Playback-only source, mute/denial, A/V behavior and cleanup. Run only after explicit permission to resume audio checks. |
| Cleanup/build | Close sockets/threads/native handles/TURN allocations; remove sharing notification after Stop. Local portable EXE and same-key APK; clean dist only after successful build. |

## Verification of this handoff

- Source and documentation reviewed for the two recorded repository revisions.
- Reference material checked: native WebRTC build requirements, peer connections,
  WebRTC authentication guidance, Railway TCP proxy and TURN project documentation.
- Markdown/project-agent guidance only. Static checks on 10 October 2026:
  `git diff --check` passed, local Markdown link targets resolved, and AGENTS.md,
  CLAUDE.md and .claude.md matched byte-for-byte.
- No application code, dependency, runtime configuration or version changes.
- No builds, tests, app/emulator launches, audio checks, live probes, deployment or
  release performed for this PR. The existing 0.7.1 distribution stays as delivered.

After a later implementation, replace the last two bullets with dated evidence
that accurately describes that work, keeping historical claims clearly scoped.
Update [architecture](../ARCHITECTURE.md), [protocol](../PROTOCOL.md),
[security](../../SECURITY.md), [platforms](../PLATFORMS.md),
[performance](../PERFORMANCE.md), [files/clipboard](../FILES_AND_CLIPBOARD.md),
[verification](../VERIFICATION.md), [third-party notices](../../THIRD_PARTY.md)
and release instructions as their behavior changes. Keep this plan labeled planned
until implementation and acceptance evidence justify changing its status.
