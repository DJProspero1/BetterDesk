# Direct connections: agent handoff and work checklist

Last updated: 11 October 2026. State: **implementation in progress; draft PR**.
Direct screen/sound/input/file routing is not enabled. Read the
[proposed design](DIRECT_CONNECTIONS.md) and [native proof](../../experimental/README.md)
before coding.

## Resume here

The owner wants direct connections between different networks, with automatic
relay fallback and the same simple Windows/Android flow. They resumed implementation,
tests and audio checks on 10 October 2026. This handoff does not claim that P2P is
implemented, measured, deployed or ready to ship.

1. Read [AGENTS.md](../../AGENTS.md), [PRODUCT.md](../../PRODUCT.md),
   [DESIGN.md](../../DESIGN.md) and [SECURITY.md](../../SECURITY.md).
2. Fetch origin and pull the primary checkout with `--ff-only` before inspecting
   repository state. Work on a task worktree/branch based on `origin/main`, then
   incorporate the PR head without overwriting another person's work. Inspect
   current changes and reviews before continuing.
3. Recheck baseline claims against source. This plan started from app commit
   `87f57b7b220eac3604a3b25dd35b1c16652d2491`, release 0.7.1, protocol 4. The
   sibling infrastructure source inspected was
   `6a2f50c00a531900976687ea049a5632f0fbff6b`; no infrastructure change is in this PR.
4. Continue **M0/M2** below: finish native integration/build feasibility and proof
   evidence before connecting the adapter to production. `SessionTransport` is
   extracted, but `HelpSession` still owns and uses the existing TLS path.
5. Update this checklist, decisions and coverage after each meaningful change.
   Mark completed work only when its stated exit evidence exists. Keep the PR a
   draft until implementation and the appropriate acceptance checks pass.

Suggested continuation prompt:

> Continue the direct Internet connection feature from this PR. Read
> docs/plans/DIRECT_CONNECTIONS_HANDOFF.md and the linked design, inspect current
> work, then take the first incomplete milestone. Improve the plan where evidence
> warrants it and update its decisions and status. Preserve the shared Windows /
> Android flow and local-build policy. Tests and audio checks were resumed by the
> owner on 10 October 2026; use synthetic content and bounded local workloads.
> Report implemented, statically checked and still-unverified work separately.

### Active operational constraints

- The owner explicitly resumed tests, emulators/app checks and audio checks on
  10 October 2026. Earlier 0.7/0.7.1 missing runtime coverage is still unverified;
  do not reinterpret old compilation evidence as a new test result.
- The owner subsequently chose to continue Linux/Android checks while deferring
  Windows evaluation registration and Cloudflare signup/provisioning. Do not ask
  for those again or resume them without new direction. Native Windows and public
  TURN checks remain unavailable; other authorized local work can continue.
- No development servers without an explicit request. No deployment, provider
  provisioning, DNS change, release or app version bump has been made. Experimental
  dependencies are opt-in; the original handoff was documentation-only.
- The owner authorized bounded local Linux compilation/synthetic tests as a
  workaround because no Windows host is reachable. See the native proof command.
  Windows packaging still needs its native toolchain. The Windows bounded command
  for compilation/packaging without tests is:

  ```powershell
  .\scripts\build-local.ps1 -SkipTests -LowResource
  ```

  That command packages artifacts and cleans dist safely; it is not needed for
  documentation changes. `-LowResource` explicitly sets one Gradle worker and
  a 1536 MiB heap; ordinary Gradle defaults are four workers/a 3 GiB heap. A future
  WebRTC source build needs its own bounded resource
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
| M0 | In progress | Native library/binding decision and local build feasibility | Pinned desktop/Android artifact/ABI/size/hash inventory and opt-in adapters exist. Finish compilation/runtime evidence, native license/build provenance and media bridge feasibility before marking complete. |
| M1 | Complete | Extract transport boundary, retaining current TLS behavior | `SessionTransport` extracted; `Wire` retains protocol 4/TLS framing and Stop still closes setup sockets. Both target source compilation and ordinary approval/Stop/epoch/input checks passed locally; native Windows-only integration remains untested. |
| M2 | In progress | Authenticated WebRTC proof behind a development-only opt-in | Twelve Linux proof checks, Android native data/video/audio peers and three desktop/Android host checks passed with real JNI and synthetic media. Pinned TLS/context validation and teardown are covered. Native Windows load/runtime evidence remains required; no production route label. |
| M3 | In progress | Screen and playback audio tracks in both directions | Opt-in bounded I420/native-video adapters exist on both targets with synthetic pixel/RTP checks. Bounded external synthetic PCM adapters also exist, with hardware microphone capture disabled. No OS screen capture/rendering, real playback capture or production freshness/epoch integration yet. Physical device/performance evidence remains required. |
| M4 | In progress | Direct control/clipboard/files and safe route cutover | Opt-in bounded BDC1 control framing carries existing synthetic messages; replay/context/UTF-8/Stop checks exist. Production input/paste, verified files, bulk scheduling and cutover remain unimplemented. No claim of all-direct while file bytes still relay. |
| M5 | In progress | HTTPS signaling and deployable TURN fallback in sibling infra PR | Internal Cloudflare credential client and nine synthetic checks exist in the companion draft; service does not import it. Approved-session issuance, HTTPS authenticated signaling, provisioning, external UDP/TCP/TLS reachability and deployment remain incomplete. Current Railway TCP bootstrap is not sufficient. |
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
| Relay | Preserve legacy fallback; select managed Cloudflare TURN | Owner has no Cloudflare account. Account creation/key access, scoped credential issuance/rotation, quotas, billing controls and external reachability remain required. Railway public HTTP/TCP proxies do not provide the needed public relay UDP range |
| Capture bridge | Preserve platform permission/service ownership | Codec/surface bridge, Windows hardware encoder and Android receive path |
| Cutover | Explicit transport-generation commit inside one approved session | Exact failure/rollback state machine; file and input ordering policy |
| Old clients | No silent schema extensions or downgrade | Deliberate compatibility behavior or clear update requirement |

## Acceptance matrix, now authorized but not yet completed

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

## Historical verification of the original planning commit

- Source and documentation reviewed for the two recorded repository revisions.
- Reference material checked: native WebRTC build requirements, peer connections,
  WebRTC authentication guidance, Railway TCP proxy and TURN project documentation.
- Markdown/project-agent guidance only. Static checks on 10 October 2026:
  `git diff --check` passed, local Markdown link targets resolved, and AGENTS.md,
  CLAUDE.md and .claude.md matched byte-for-byte.
- No application code, dependency, runtime configuration or version changes.
- No builds, tests, app/emulator launches, audio checks, live probes, deployment or
  release performed for this PR. The existing 0.7.1 distribution stays as delivered.

The bullets above describe only the original planning commit, not later code.
Current implementation evidence belongs below and in the native proof guide.
Update [architecture](../ARCHITECTURE.md), [protocol](../PROTOCOL.md),
[security](../../SECURITY.md), [platforms](../PLATFORMS.md),
[performance](../PERFORMANCE.md), [files/clipboard](../FILES_AND_CLIPBOARD.md),
[verification](../VERIFICATION.md), [third-party notices](../../THIRD_PARTY.md)
and release instructions as their behavior changes. Keep this plan labeled planned
until implementation and acceptance evidence justify changing its status.

## Implementation evidence, 10 October 2026

### Continuation, 11 October 2026

- Added opt-in BDC1 control framing for the existing message schema, with bounded
  fragments/reassembly/writers, fixed generation and consecutive sequences,
  strict UTF-8/schema rejection, one deadline per message and redacted errors.
  Fixed late inbound callbacks racing Stop queue clearing. Synthetic input,
  clipboard/barrier and switch/status messages are transported, never executed.
- Final Linux suite: 76 checks, 66 passed, zero failed, ten explicit skips.
  Thirteen native proof and four framing checks passed. Three Android-only native
  checks and all three desktop/Android interoperation profiles passed on the exact
  final APKs; microphone permission stayed denied. See [verification](../VERIFICATION.md)
  for hashes, timings and coverage. The owned emulator was stopped after checks.
- Both PRs remain draft, with no new review findings. Automated review is unavailable
  because of account review limits; no review or approval was obtained. M4 is now
  in progress, but production cutover, real input/paste, bulk files and M3/M5/M6
  completion remain required before merging the feature. Windows and Cloudflare
  account deferrals remain in effect.

### Earlier implementation

- Owner resumed implementation, tests and audio checks; delegated TURN choice and
  authorized a local workaround for the unavailable Windows host.
- `SessionTransport` separates packet operations/peer addressing from concrete
  `Wire`, with unchanged session consent and TLS protocol 4.
- Native adapters and fixture exist only under `-Pbetterdesk.rtcProof=true`.
  Normal builds retain the existing dependency set and transport behavior.
- Initial local desktop source compilation passed. Initial suite: 55 checks,
  39 passed, 10 failed, six opt-in checks skipped. Failures were Linux native
  decoder check constructing the Windows platform on Linux, and missing Node
  fixture dependencies. The rerun passed the three native proof checks and nine
  broker checks (51 passed, one failed, six skipped); the remaining Windows
  decoder-wrapper fixture was split into a portable decoder check plus an explicit
  Windows-only integration check. Final suite: 62 checks, 54 passed, eight skipped,
  no failures. All five native proof checks passed (4.383 seconds). Skips include
  six existing opt-in checks, Windows platform integration and the dedicated-device
  host proof. The latter passed separately after the final fixes below.
- Opt-in Android source/test compilation and both APK packages passed. A fresh API
  36.1 Android 16 emulator booted; initial install commands timed out but the APK
  later appeared in the package manager. Retry with four GiB RAM/default Vulkan
  completed boot. A boot-readiness guard was added. Native Android initialization
  then exposed conflicting injected/native logging setup, which was fixed without
  enabling debug logs. Both adapters now process gathering callbacks only at
  COMPLETE. The full suite passed again; final APKs passed Android native peers
  (0.355 seconds) and desktop/Android native interoperation (4.833 seconds), including
  authenticated setup, synthetic bytes and teardown. Exact APK hashes are in
  [verification](../VERIFICATION.md).
- Sibling infrastructure baseline passed all ten checks. Subsequent internal TURN
  provider preparation passed all 19 checks, including nine new synthetic checks.
  [Infrastructure PR #1](https://github.com/DJProspero1/BetterDesk-Infrastructure/pull/1)
  is a draft; `src/main.mjs`/broker do not import the module and no endpoint,
  production configuration or deployment changed. Cloudflare provisioning needs
  a new owner account. The owner deferred signup and provisioning; no credentials
  were entered or read.
- A local Windows VM was investigated, but Microsoft's official media download
  retail flow returned an error and Access Denied. The official Enterprise
  evaluation route requires owner contact/company registration. The owner chose
  to continue Linux checks and defer it. No Windows VM/media was installed.
- GitHub Codex review was requested on PR #1 but did not run because account review
  usage limits were exhausted. No review approval or passing remote checks exist.
- No production signaling/route/media/input/file implementation, infrastructure
  deployment, version bump, portable Windows build or app release yet.
- The next synthetic-video checkpoint added bounded I420 sources/sinks to both
  adapters, explicit video signaling version 2, bundled identity checks and
  aggregate RTP counters. Final suite: 67 checks, 58 passed, nine skipped, no
  failures. Final APKs passed two Android-only native checks (0.441 seconds),
  desktop/Android data (3.057 seconds) and video (2.864 seconds) interoperation.
  Exact APK hashes and scope are in verification. No actual screen capture/render,
  playback sound or input freshness/epoch integration is attached to the tracks.

- The synthetic audio/video checkpoint adds bounded external PCM sources on both
  adapters, proof signaling profile 3 and audio RTP/decoded-peak checks without
  hardware microphone capture. Android requires a 10 ms external callback clock
  and audio-track/source disposal before factory destruction. Expired PCM drops
  without closing the connection; fresh audio recovery is tested. Final suite:
  71 checks, 61 passed, ten skipped, zero failures; all twelve native proof checks
  passed. Final APKs passed three Android-only checks (3.105 seconds) and all three
  desktop/Android host checks: data 5.980 seconds, video 5.716 seconds, audio/video
  9.447 seconds. Installed hashes, microphone denial and fixture cleanup were
  verified; see the exact hashes/scope in verification. These timings include
  fixture setup and do not measure latency or delivered media performance.
- Existing shared Stop regressions exposed I/O races between queued input, media,
  heartbeat writes and read EOF. Established transport I/O failures now end and
  revoke the session consistently; setup/authentication and non-I/O protocol
  validation retain their error behavior. Both strict Ended regressions passed.
