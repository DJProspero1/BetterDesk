# BetterDesk project rules

These rules apply to work in this repository. Read [PRODUCT.md](PRODUCT.md) and
[DESIGN.md](DESIGN.md) before changing the experience, and
[SECURITY.md](SECURITY.md) before changing capture, control or transport.

## Keep agent instructions identical

- `AGENTS.md` is the editing source. `CLAUDE.md` and `.claude.md` are byte-identical
  mirrors, not summaries. `CLAUDE.md` is the conventional Claude entry point;
  `.claude.md` is retained as the explicitly requested filename.
- After editing these rules, run `./scripts/check-agent-instructions.ps1 -Sync`,
  then run it without `-Sync`. Commit all three files together.
- Never add tool-specific exceptions or independent guidance to a mirror. Local
  builds reject drift; the tracked pre-commit hook checks the staged copies.
- On a new checkout, enable that hook with `git config core.hooksPath .githooks`.
  Inspect an existing hook configuration before replacing it. No global Git
  configuration is required. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Product and scope

- Build BetterDesk: simple, attended remote access between Windows and Android in
  both directions. Ordinary people, including nontechnical accountants, must be
  able to open it and connect without outside instructions.
- Keep the product small. Favor fewer steps, plain words and automatic setup over
  dashboards, extra settings, onboarding lectures or accounts.
- Do not make the user select a helper/recipient role. Both apps show this device's
  code, a field for another device's code, and Connect. The requested device
  approves. Do not reintroduce invitation links, messaging-app round trips or role
  wording as the normal connection flow.
- Automate technical work in the background when possible. Real OS permissions
  and local session approval remain visible and intentional; automation never
  means bypassing consent or pretending that a step succeeded.

## Windows and Android are one experience

- Apply every feature, bug fix, design change, copy change and preference rule to
  both apps by default. A platform-specific request is the exception only when
  explicitly scoped or required by the OS; document that reason.
- Reuse shared Kotlin/Compose components, copy, ordering and session behavior.
  Keep capture, input, permissions and packaging behind platform interfaces. Do
  not create two independently evolving frontends.
- Adapt to screen size, touch, keyboard, rotation and system insets while keeping
  the same flow. Familiarity with one app must transfer to the other.
- Prefer native Android integrations where they improve quality. Use the actual
  system capture, permission, notification and lifecycle APIs; avoid adding broad
  permissions or background work without a concrete benefit.

## Design and preferences

- Use the shared black/charcoal interface, high-contrast neutral controls and
  short, literal copy. Do not reintroduce green branding, clutter, decorative
  metrics, remote fonts or technical implementation details in ordinary flows.
- Viewing uses the full available display, including Android landscape. Hide the
  permanent app header/footer. Fit the remote image without cropping, distortion
  or color changes; different aspect ratios can leave black margins.
- Controls overlay the image on demand and must not shrink it. Keep Disconnect
  reachable, respect cutouts and the keyboard, and restore normal bars/window
  placement when viewing ends.
- Use one computer/connection icon across the UI, Android launcher and
  notifications, Windows executable and taskbar. Edit `assets/icon-paths.json`
  and regenerate with `scripts/generate-icons.ps1`; do not let variants drift.
- Save explicit preferences across app switches, rotation, restarts and updates.
  Initial defaults may follow the system; later launches honor the user's choice.
- Keep English and European Portuguese copy in step. The language selector uses
  locally drawn flags plus an accessible language name and action; do not rely
  on platform-dependent flag emoji.
- Preserve large touch targets, keyboard focus, screen-reader labels, scalable
  text and status that does not depend only on color.

## Connections, lifecycle and performance

- Prefer a reachable private LAN automatically, with bounded discovery/pairing
  and automatic Railway fallback before approval. Do not add a network picker or
  router setup to the ordinary flow. A local failure must not leave users waiting
  indefinitely or produce duplicate approval requests.
- Switching apps or rotating a phone must not end a pending/active connection.
  Explicit disconnect, expiry, screen lock, permission revocation and OS process
  termination have defined cleanup; never imply that process survival is guaranteed.
- Android must show an actionable notification while sharing and remove it when
  sharing stops on either device. Avoid duplicate availability/sharing notices.
  Explain the effect of denied notification permission honestly.
- Explain required Android permission steps inside the app before opening
  Settings, including restricted-settings recovery. Returning rechecks the real
  permission. View-only sharing stays available when control is unavailable.
- Treat responsiveness as a core feature. Aim for an average of at least 60
  distinct drawn frames per second during sustained motion on capable devices
  and networks. A configured encoder rate is not a measured delivered rate.
- Measure decoded/drawn FPS, frame gaps, bandwidth and relevant latency. Preserve
  readable detail and input responsiveness; disclose resolution/quality tradeoffs.
  Never advertise universal 60 FPS or call an emulator result a physical-device result.
- Keep queues bounded, drop/recover stale video safely, revoke stale input, and
  release sockets, codecs, wake locks and foreground services on every stop/error.

## Security and privacy

- Require explicit local approval before capture or input. No unattended access,
  hidden control, OS prompt bypass, fabricated permissions or self-approval.
- Preserve code-authenticated pairing, pinned end-to-end TLS, short-lived
  capabilities, strict protocol bounds and fail-closed input validation. Do not
  weaken host validation or cryptography to make a test pass.
- Never log or commit connection codes, invitations, credentials, tickets, screen
  contents or typed text. Keep secrets outside source/build outputs and use
  synthetic content for test screenshots. Aggregate local performance counters
  are allowed; telemetry and screen recording are not.
- Do not add automatic clipboard reading, file transfer, microphone access or
  persistent access without a new product decision. Remote input must not operate
  BetterDesk's own approval controls or bypass OS security screens.
- Stop revokes input and capture promptly; do not quietly reconnect or authorize
  another session. A stored accessibility grant is not future-session consent.
- This is an early implementation, not externally audited software. Document
  security limitations and the scope of evidence rather than inventing assurances.

## Development, hosting and delivery

- Run all native compilation, tests, signing and packaging locally. Never add
  cloud native-app builds or GitHub Actions. Dependency downloads are permitted.
- Do not start, restart or launch development servers without an explicit request.
  Automated tests may create temporary bounded loopback fixtures and must close them.
- Windows delivery is one portable EXE with its runtime, no installer and no
  user-managed ZIP. A private extraction cache is acceptable; explain it accurately.
- Android delivery is a local APK update. Preserve compatible app identity and
  signing continuity; do not silently uninstall to solve an update error. Ordinary
  Android update/permission confirmation cannot be bypassed.
- Source may be committed and pushed to the existing private GitHub repository
  under the owner's authorization. Keep it private until explicitly told otherwise.
  Potential open-source readiness does not authorize publication.
- Keep app binaries local. Do not publish GitHub release assets or download links.
  The separate BetterDesk website on Vercel is a waitlist, not a download portal.
- Keep website and Railway infrastructure in separate projects/repositories.
  Railway hosts connection services and the waitlist database; Vercel hosts the
  website. Cloud infrastructure hosting does not authorize cloud native builds.
- Preserve unrelated work and existing user configuration. Do not commit secrets,
  local SDK paths, signing keys, caches, generated builds or private test evidence.

## Testing and documentation

- Test every code change with checks relevant to its behavior. Check both native
  targets for shared changes. Run the ordinary suite and local builds before a
  delivery; add platform/transport checks where the change requires them.
- Verify the actual final binaries and deployment behavior, not merely successful
  command exits. Do not reuse a cached device-test result after changing its APK.
- When deploying an authorized infrastructure/website change, verify configuration,
  health and the real affected flow. Do not deploy unrelated projects as a side effect.
- Report passed, skipped and untested coverage separately. Never present synthetic
  fixtures as a real user's device, a visual check as a latency benchmark, or a
  target as an achieved result. Physical/OEM and restrictive-network gaps matter.
- Document for a contributor with no chat history: setup, architecture, protocol,
  platform differences, data handling, tests, releases, troubleshooting and limits.
- Documentation must always be up to date with the implementation. Every change
  to behavior, UI, configuration, dependencies, architecture, testing, builds or
  deployment must update the affected documentation in the same change.
- Documentation is part of the definition of done, not deferred follow-up work.
  Before marking work complete, check affected guides, examples, commands, links
  and limitations; correct or remove obsolete information. Keep historical test
  results clearly dated and do not present them as current verification.
- Keep commands reproducible and paths generic. Preserve the MIT source license
  and dependency notices. Check redistribution obligations before publishing;
  documentation readiness is not a completed license/security audit.
- Follow [CONTRIBUTING.md](CONTRIBUTING.md) and the linked technical guides. Before
  committing, check instruction parity, documentation links and `git diff --check`.
