# BetterDesk contributor instructions

- Read PRODUCT.md and DESIGN.md before changing UX. Windows and Android must use shared UI and identical copy/flow.
- All builds, tests, signing and packaging run locally. Never add cloud CI builds or GitHub Actions.
- Do not start development servers unless explicitly requested. Automated test fixtures may create temporary loopback connections and must close them.
- No unattended access, public relay, screen recording, automatic clipboard reading, telemetry, or authentication bypass.
- Never log invitations, tokens, screen contents, or typed text.
- Declare the current local-network scope honestly. Never display a simulated remote session as real.
- Private GitHub source hosting and pushing from this Windows PC are authorized by the user. Do not publish publicly.
