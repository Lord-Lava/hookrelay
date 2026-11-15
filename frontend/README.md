# HookRelay dashboard (planned)

The customer-facing Next.js application will be scaffolded after the first backend milestone is verified.

## Planned baseline (verified 2026-11-13)

- Next.js 16.4.6 (latest stable at review date; do not use canary/nightly builds for production)
- Node.js 26.13.0 LTS
- TypeScript (pin exact compatible patch when the app is scaffolded)
- React version must be the one required/peer-compatible with the chosen Next.js patch; do not independently upgrade React without verifying Next.js compatibility.

No `package.json`, dependency tree, or lockfile exists yet. When scaffolding, commit a single package-manager lockfile, pin Node via `.nvmrc`/`.node-version`, and use `npm ci` (or the chosen manager's frozen-lockfile equivalent) in CI.

Planned areas:

- Organization and project onboarding
- Endpoint configuration and event subscriptions
- Event explorer and delivery timeline
- Delivery failures and replay controls
- Usage, quotas, and billing

The dashboard must use a separate user/session authentication flow. Do not expose project API keys to browser code.
