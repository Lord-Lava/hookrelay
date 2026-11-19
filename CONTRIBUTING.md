# Contributing to HookRelay

## Branching strategy

HookRelay uses **GitHub Flow**: `main` is the default, production-ready branch. All changes enter via pull requests. There is no separate `develop` or `master` branch.

### Long-lived branches

| Branch | Purpose | Protected |
|--------|---------|-----------|
| `main` | Default branch; production-ready state | Yes |

### Short-lived feature branches

Create a branch from `main` for every change:

- `feat/…` — new functionality
- `fix/…` — bug fixes
- `docs/…` — documentation changes
- `chore/…` — build/CI/dependency maintenance
- `refactor/…` — internal code improvements with no behavior change
- `security/…` — security-related fixes

Naming example: `feat/webhook-retry-policy`, `fix/ssrf-dns-mixed-answers`

### Branch lifecycle

1. `git checkout -b feat/my-change main`
2. Implement changes with tests.
3. Run `./gradlew spotlessApply clean check bootJar` from `backend/`.
4. Push the branch: `git push -u origin feat/my-change`
5. Open a pull request targeting `main`.
6. Address review feedback; ensure CI passes on the PR.
7. Maintainer squash-merges or rebase-merges the PR.
8. The feature branch is auto-deleted after merge.

## Pull request requirements

Every PR targeting `main` must satisfy the branch protection rules:

- **Status checks:** `build`, `dependencies`, and `static-analysis` must all pass.
- **Review:** At least one approving review is required.
- **Conversation:** All review comments must be resolved.
- **No force pushes** or branch deletions on `main`.

PRs should:

- Reference the related issue or design decision in the description.
- Include unit, integration, or contract tests for new behavior.
- Update `docs/architecture.md`, `docs/openapi.yaml`, or ADRs when behavior or contracts change.
- Keep changes focused — one logical change per PR.

## Direct commits to main

Direct commits to `main` are prohibited for all users, including administrators. Every change must flow through a PR.

## Repository settings

- Default branch: `main`
- Merge methods enabled: squash, rebase, merge
- Auto-delete head branches after merge: disabled (branches are cleaned up manually after merge)

## Security

Do not commit secrets, credentials, API keys, or real customer payloads. Use `.env.example` placeholders only. Report security vulnerabilities through the project's security advisory process, not through public issues or PRs.

## Before your first contribution

1. Fork the repository (if you are an external contributor).
2. Clone your fork: `git clone https://github.com/<your-username>/hookrelay.git`
3. Add the upstream remote: `git remote add upstream https://github.com/Lord-Lava/hookrelay.git`
4. Sync with upstream before starting: `git fetch upstream && git checkout main && git merge upstream/main`
