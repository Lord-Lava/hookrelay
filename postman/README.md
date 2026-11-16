# HookRelay HTTP collections

The comprehensive collection has 52 requests; the smoke collection has 23.
Both mutate data: they create endpoints and subscriptions, update state, rotate a
signing secret, ingest events and delete their endpoints. Run them only against
an isolated test environment. Events and audit rows remain until that test database
is destroyed. An interrupted run may also leave endpoints behind.

## Reproducible local verification

From the repository root, after running the backend tests and building the image:

```sh
node postman/check-collections.mjs
python3 scripts/verify-compose.py --image hookrelay-review:local --newman newman
```

The runner starts a uniquely named disposable Compose project with PostgreSQL and
RabbitMQ, checks the built application's health and API behavior, and seeds two
projects with randomly generated credentials. It runs smoke twice and comprehensive
once, failing on any assertion. It always removes its own containers and volumes.
It does not access the normal development Compose project. Credentials live only
in mode-0600 temporary files and are removed on exit.

Newman is an optional external tool; it is not a production or build dependency.
CI uses the real HTTP integration tests, collection syntax checks, and the Compose
image smoke test without installing Newman. Review the advisories of your installed
Postman/Newman version before use; Newman 6.2.3's published dependency graph currently
contains High/Critical advisories, so it has not been admitted as a CI dependency.
Do not run arbitrary third-party collections or give this runner production secrets.

## Manual Postman use

The filenames contain literal `%20` characters. Import these exact files:

- `HookRelay%20API.postman_collection.json`
- `HookRelay%20Smoke%20Tests.postman_collection.json`
- `environments/HookRelay%20Development.postman_environment.json`

Set `base_url`, `api_key`, `project_id` (project A), and `other_api_key` (project B).
The API keys must belong to different projects. Both environment files contain only
placeholders. The file named Production is a template, not permission to run these
mutating tests against production.

Each run creates unique URLs and idempotency keys. Runtime IDs use local collection
variables; credentials and returned signing secrets are never saved by test scripts.
Cross-project tests first create a real endpoint and subscription using project B,
prove ownership with a successful read, then attempt access using project A.
Length tests send valid JSON and require the relevant field's validation error.
Successful create operations require 201; conflicts cannot silently count as setup.

To run one collection manually:

```sh
newman run 'postman/HookRelay%20API.postman_collection.json' \
  -e /path/to/private-test-environment.json --bail --timeout-request 15000
```

The API contract is maintained in `docs/openapi.yaml`. Backend contract tests compare
its operations, DTO fields and length constraints with the implementation. Collection
lint parses raw JSON bodies and JavaScript scripts; it does not claim runtime coverage.
