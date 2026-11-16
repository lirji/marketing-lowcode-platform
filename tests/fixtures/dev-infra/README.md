# CI infrastructure fixture

This is the 2026-11-16 snapshot of the existing shared `dev-infra` configuration,
reduced to Marketing's MySQL 8.4, Redis 7, Kafka 3.8 and MinIO dependencies plus
its existing Marketing configuration. `source-manifest.json` records the captured
Marketing file hashes. The network helper implements only the operation required
by `marketing-infra`. This directory is explicitly selected by GitHub Actions;
local scripts still default to the sibling shared `dev-infra` directory.

The fixture replaces the unavailable cross-repository checkout, so a clean Runner
can render, migrate and start the project without private infrastructure credentials.
The workflow generates temporary passwords; `.env.example` contains placeholders.
No real local `.env`, database contents or private keys are included. Update this
snapshot with relevant shared-infrastructure changes, review the diff, and rerun
Compose/Helm, migration and full-stack checks. It is not a production deployment.

The historical Docker Hub and Quay MinIO images both failed registry verification
on 2026-11-16. CI follows the [upstream source-only distribution](https://github.com/minio/minio#source-only-distribution):
MinIO `9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a` (the
[2025-10-15 security release](https://github.com/minio/minio/releases/tag/RELEASE.2025-10-15T17-29-55Z))
and mc `7394ce0dd2a80935aded936b09fa12cbb3cb8096` (2025-08-13) are built with
Go 1.27.4 and packaged in the digest-pinned [CI Dockerfile](../minio/Dockerfile).
Licenses are retained. Only the CI MinIO image, readiness probe and provisioning
client differ from the captured infrastructure; existing shared containers are
not upgraded. This source build and its temporary credentials are CI tooling,
not a supported production storage choice. The upstream project is archived.

The secret scanner retains full history and its default rules. The thirteen entries
in [`.gitleaksignore`](../../../.gitleaksignore) identify only reviewed historical
fingerprints: five source hashes in two historical commits, two synthetic k6 subject
IDs and a throwaway database test password. No directories or rules are excluded; real findings must
still fail the workflow.

Full-stack CI uses the read-only [catalog contract fixture](../benefit-catalog/server.py)
for `retail-cn`, with a random machine token and one synthetic CASH SKU. Every
Marketing service and database, queue, Flink job, signature/approval and accounting
operation runs normally. This proves the core R1 integration with that contract
fixture; it does not prove real external awards, production authentication, or
external catalog availability. The existing real local catalog verification is a
separate piece of evidence. Sandbox contact delivery remains sandbox delivery.

`../ci-resources.compose.json` bounds application memory for the hosted Runner and
keeps the existing three Flink jobs, six slots and checkpoint behavior. It is
selected through `MARKETING_COMPOSE_OVERRIDE`; ordinary local resource defaults
are unchanged. The full-stack job uses new acceptance IDs for both runs; only the
individual assertions about keyed replay prove idempotency.
