# API and event contracts

| Contract | Audience | File |
|---|---|---|
| Webhook endpoints | Payment providers, API Manager | [`webhooks.openapi.yaml`](webhooks.openapi.yaml) |
| Canonical payment event | Finance, Notifications, Salesforce writer | [`events/premium-payment.schema.json`](events/premium-payment.schema.json) |

The implementation in [`practical/`](../../practical/) validates every event it produces against the JSON Schema in its tests, so the contract and the code cannot drift silently.

## How the webhook contract evolves without breaking providers

- **Meridian adapts to providers, never the reverse.** Each provider keeps its native payload and signature; the contract documents what Meridian reads and allows additional properties.
- **Tolerant reader**: unknown fields are ignored (Andina 2.3 added `canal`), unknown event types or states are acknowledged with `200 IGNORED` and counted, so a provider extending its API never triggers retry storms.
- **Provider API versions are pinned** where the provider allows it (Quetzal `api_version`). A provider's breaking change (e.g. Andina 2.1 changing the signed content) is handled by deploying an adapter that accepts both formats during the provider's transition window, then removing the old one.
- **Semantic changes are watched, not only schema changes**: Andina 2.3 kept the schema but changed the meaning of `referencia`. Provider changelogs are reviewed as part of the squad's routine, and the adapter tests include the provider's published samples.
- **The path is versioned** (`/v1`). A new path version is only needed if Meridian changes its own behaviour towards providers (e.g. the acknowledgement contract); providers would then be migrated one by one.

## How the canonical event evolves without breaking consumers

- **Versioning**: CloudEvents `type` carries the **major** version (`…status-changed.v1`); `dataschema` carries the exact **semver** (`…/premium-payment/1.0.0`).
- **Minor versions (1.x)** only add **optional** fields or new enum values documented as "ignore if unknown". Consumers must be tolerant readers (ignore unknown fields). Removing or renaming a field, changing a type or the meaning of a field, or making a field required is a **major** change.
- **Major versions** are published **side by side** (`.v1` and `.v2` on the same topic, distinguished by the `type` application property, so subscriptions can filter) until every consumer has migrated; then `.v1` is retired with notice.
- **Compatibility check in CI**: the schema in `main` is compared with the schema in the pull request; a breaking change without a major version bump fails the build.
- **Consumers subscribe on their own**: each owns a Service Bus subscription with its own filter and DLQ, so a slow or broken consumer never affects the others or the Salesforce writer.
