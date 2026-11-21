# Infrastructure as code

Azure resources for the Premium Payment Notifications design ([SOLUTION_DESIGN](../docs/design/SOLUTION_DESIGN.md)). Service choices, alternatives, SKUs and costs are in [`docs/infra/AZURE_SERVICES.md`](../docs/infra/AZURE_SERVICES.md). **Nothing is deployed.**

## Tool and version

**Bicep**, validated with **Bicep CLI 0.49.2**. Bicep was chosen over Terraform because the target is Azure only, it needs no state file to secure and operate, and it gets new Azure API versions on day one. The trade-off: no multi-cloud, and drift is detected with `what-if` rather than a state diff.

## Structure

```
infra/
├── main.bicep                 Composition: identities, modules, wiring (resource group scope)
├── env/
│   ├── dev.bicepparam         Smallest sizes, no HA, email alerts only
│   ├── test.bicepparam        Production topology at reduced size (peak load tests)
│   └── prod.bicepparam        Zone-redundant DB, always-warm intake, on-call paging
└── modules/
    ├── network.bicep          VNet, Container Apps subnet (Key Vault service endpoint), delegated PostgreSQL subnet
    ├── servicebus.bicep       Namespace (Entra ID only), inbound queue, events topic, writer subscription, DLQs, entity-scoped RBAC
    ├── keyvault.bicep         RBAC-mode vault, firewall (deny + apps subnet), purge protection (test/prod), Secrets User role
    ├── postgres.bicep         Flexible Server 16, private access + private DNS, Entra ID auth only, HA/backup per env, TLS enforced
    ├── containerapps.bicep    Environment (internal, VNet) + intake, processor, sf-writer, ops-api; KEDA scaling with managed identity
    ├── monitoring.bicep       Application Insights (platform workspace), action group, metric and per-country log alerts, budget
    └── acr-pull.bicep         AcrPull on the shared registry for each component identity
```

## Parameters per environment and per country

| Parameter | dev | test | prod |
|---|---|---|---|
| PostgreSQL | B1ms Burstable, no HA, 7-day backups | B2s Burstable, no HA, 7 days | D2ds_v5 General Purpose, **zone-redundant HA**, 35 days |
| Intake replicas (min–max) | 1–2 | 2–5 | **2–10** (no cold start for Andina's 5 s timeout) |
| Processor max replicas | 2 | 3 | 5 |
| Salesforce writer max replicas | 1 | 2 | **2** (hard cap: Salesforce concurrency agreement, ADR-0001) |
| Key Vault purge protection | off | on | on |
| Alert severity / paging | Sev 3, email | Sev 3, email | Sev 1–2, email + on-call webhook |
| Monthly budget (USD) | 150 | 250 | 700 |

**Countries** are a parameter, not a deployment: `providers` in `main.bicep` lists `{ name, country, secretName }` for Quetzal (MX), Andina (CO) and Austral (CL). For each entry the intake app gets the matching Key Vault reference as `PAYMENTS_INTAKE_PROVIDERS_<NAME>_SECRETS_0`. Adding a country means adding one entry and one Key Vault secret (NFR9). Country is a dimension of every alert query and metric, so all countries are always monitored (lesson from INC-0412, where only Mexico had a DLQ alert).

Values that differ per tenant (subscription ids, platform workspace, registry, Entra group ids) are placeholders (`00000000-…`) to be replaced by the platform team.

## Identities and least privilege

| Identity | Permissions | Scope |
|---|---|---|
| `id-intake` | Service Bus Data **Sender**; Key Vault Secrets User; AcrPull | inbound **queue** only; vault; registry |
| `id-processor` | Service Bus Data **Receiver**; Data **Sender**; PostgreSQL role `payments_rw` | inbound queue; events **topic**; database |
| `id-writer` | Service Bus Data **Receiver**; Key Vault Secrets User; PostgreSQL role `payments_rw` | **its subscription** only; vault; database |
| `id-opsapi` | PostgreSQL role `payments_ro` (+ a narrow write grant on `payment_state.policy_number`) | database |
| CI/CD (`github-oidc-deployer`) | Contributor + Role Based Access Control Administrator (restricted to the role definitions above) | resource group of each environment |

PostgreSQL roles for managed identities are created by the first migration with `pgaadauth_create_principal(...)`, because they live inside the database, not in ARM. Finance and Notifications create their own subscriptions with their own identities, owned by their squads.

**Secrets are never in IaC.** The secret owner (payments squad lead for providers, Salesforce team for the client secret) sets `quetzal-webhook-secret`, `andina-webhook-secret`, `austral-webhook-secret` and `salesforce-client-secret` directly in Key Vault, through a temporary firewall exception. Container Apps read them as Key Vault references with the app's managed identity.

## Validation (without deploying)

| Check | Command | Result |
|---|---|---|
| Compile and type-check every module against the Azure resource schemas | `bicep build infra/main.bicep` | OK |
| Linter (default rules: secure params, no hard-coded environment URLs, API versions, unused params…) | `bicep lint infra/main.bicep` | **0 errors, 0 warnings** |
| Parameter files resolve against `main.bicep` | `IMAGE_TAG=abc123 bicep build-params infra/env/{dev,test,prod}.bicepparam` | OK for the three environments |

During validation the linter flagged that `identity` on KEDA scale rules is not supported by the Container Apps API `2024-03-01`. The modules were moved to the GA API `2025-01-01`, which supports managed-identity authentication for scale rules (no connection strings).

The same checks run in CI on every pull request (`.github/workflows/ci.yml`). The CD pipeline additionally runs `az deployment group what-if` against each environment before deploying, and prod requires manual approval of that output.

**Not validated** (requires a subscription): ARM preflight, `what-if`, policy compliance of the landing zone, and real role-assignment propagation.
