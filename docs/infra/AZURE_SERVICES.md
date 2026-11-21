# Azure services

How each service of the [solution design](../design/SOLUTION_DESIGN.md) was chosen, sized and secured, along the five pillars of the Azure Well-Architected Framework (WAF). IaC: [`infra/`](../../infra/).

## Summary

| Service | Role | Tier / SKU (prod) | Discarded alternatives |
|---|---|---|---|
| Azure Container Apps | Runs intake, processor, Salesforce writer, ops API | Consumption workload profile, internal VNet environment | Azure Functions, AKS, App Service |
| Azure Service Bus | Inbound queue, canonical event topic, DLQs | **Standard** | Premium, Event Hubs, Event Grid, Storage Queues |
| Azure Database for PostgreSQL Flexible Server | Inbox, payment state, outbox, FR7 log | General Purpose D2ds_v5, zone-redundant HA, 64 GB | Cosmos DB, Azure SQL Database |
| Azure Key Vault | Provider and Salesforce secrets | Standard, RBAC mode | App configuration secrets, Container Apps secrets only |
| Managed identities (user-assigned) | One per component | — | System-assigned, service principals with secrets |
| Application Insights + platform Log Analytics | Traces, metrics, logs, alerts | Workspace-based, pay-as-you-go | Separate workspace, third-party APM |
| Azure Monitor alerts + action group | SLO and loss alerts, paging | Metric alerts + scheduled query rules | — |
| Virtual network + private DNS | Private access to PostgreSQL, Key Vault firewall | — | Private endpoints everywhere (see Service Bus) |

## Per service

### Azure Container Apps

- **Why**: always-warm replicas for the intake (Andina times out at 5 s, so no cold starts), KEDA scaling on HTTP concurrency (intake) and on queue length (processor, writer), managed identity for registry, Key Vault references and Service Bus, revisions for safe rollouts. Runs standard containers, so the stack stays portable.
- **Discarded**: *Functions* (cold starts unless paying for always-ready instances; a batching, circuit-breaking writer fits poorly in a trigger model). *AKS* (cluster operations are out of proportion for four small services). *App Service* (per-plan cost even when idle, and weaker event-driven scaling).
- **Reliability**: min 2 intake replicas in prod; health probes; the writer is capped at 2 replicas so it can never exceed the Salesforce concurrency agreement. **Security**: internal environment inside the VNet, reached only through the API Manager (A-2); no secrets in env vars except Key Vault references. **Cost**: Consumption billing per vCPU-second; ops API scales to zero. **Operations**: one image, four apps (Spring profiles), single-revision mode for simple rollbacks.

### Azure Service Bus — Standard

- **Why**: queues and topics with per-entity dead-lettering, peek-lock, duplicate detection (7-day window covers every provider's retry period), scheduled messages for backoff, and subscriptions so Finance and Notifications consume independently (FR6).
- **Why Standard and not Premium**: peak load is 40 msg/s, far below Standard limits. Premium (≈ USD 680/month per messaging unit) would add private endpoints, VNet isolation and predictable latency. Instead, local/SAS auth is disabled and every client authenticates with Entra ID and entity-scoped RBAC over TLS 1.2. Messages carry **no personal data** (canonical events only), which reduces the impact of a public endpoint. **Upgrade trigger**: a security policy requiring private endpoints for all PaaS, or sustained throughput above Standard's shared capacity (single SKU change in `servicebus.bicep`).
- **Discarded**: *Event Hubs* (stream semantics: no per-message DLQ, settle or duplicate detection). *Event Grid* (push delivery with limited retry control, no consumer-side backlog). *Storage Queues* (no topics, no duplicate detection, no native DLQ).
- **Reliability**: DLQ on every entity, TTL 14 days on the queue and 7 days on the subscription (survives the longest Salesforce outage), max delivery count 10. **Operations**: DLQ and backlog alerts on all entities, covering every country.

### Azure Database for PostgreSQL Flexible Server

- **Why**: atomic dedup and ordering with `UNIQUE` + `SELECT … FOR UPDATE` and the outbox in the same transaction; indexed lookups by any provider id for FR7; open-source engine (ADR-0002).
- **Sizing**: ≈ 120 statements/s at the 40/s burst and ≈ 12 GB/year. 2 vCores General Purpose leaves a wide margin; Burstable is enough for dev/test.
- **Discarded**: *Cosmos DB* (cross-partition queries for FR7, transactional scope limited to one partition). *Azure SQL Database* (equivalent but proprietary; serverless auto-pause adds resume latency).
- **Reliability**: zone-redundant HA in prod (automatic failover); the intake does not depend on the database, so webhooks are still accepted during a failover. PITR 35 days. **Security**: private access only (delegated subnet, private DNS), Entra ID authentication only, TLS enforced, no personal data stored. **Cost**: HA doubles compute; justified in prod only.

### Azure Key Vault

- **Why**: single store for the webhook secrets of each provider and the Salesforce client secret (NFR6); Container Apps resolve them as references with managed identity, so application code needs no SDK and no secret reaches a file or pipeline log.
- **Security**: RBAC authorization (no access policies), firewall default-deny with only the Container Apps subnet allowed (service endpoint), purge protection in test/prod, audit logs to Log Analytics. Two secret versions can be active during provider rotations. **Cost**: a few cents per month.

### Identities

One user-assigned managed identity per component: the intake can only send to the inbound queue, the processor can only read that queue and publish to the topic, and the writer can only read its own subscription (see [`infra/README.md`](../../infra/README.md)). User-assigned rather than system-assigned so that role assignments exist before the apps start and survive app re-creation. The CI/CD pipeline authenticates with **OIDC federation** (no stored secret).

### Observability: Application Insights and Azure Monitor

- **Why**: the platform already runs Log Analytics; a workspace-based Application Insights component per environment keeps data in the platform workspace (one place for operations) while giving the squad its own application map, traces and alert scope.
- **Alerts**: DLQ not empty and backlog growing (platform metrics); freshness p95, dead-lettered per country, unsynced payments, signature failures and Salesforce budget (log queries, split by country). Thresholds and owners are in `docs/operations/OPERATIONS.md`.
- **Cost control**: adaptive sampling for request telemetry, never for the business events used by SLIs; 30-day interactive retention, longer retention through the platform's archive tier.

### Network

A /22 VNet per environment with a Container Apps subnet and a delegated PostgreSQL subnet. Private endpoints for Key Vault and Service Bus were not used: Service Bus Standard does not support them, and for Key Vault a service endpoint gives the same "only from our subnet" guarantee at no cost.

## Order-of-magnitude monthly cost (prod)

Assumptions: pay-as-you-go list prices in a US region (USD, rounded), 730 h per month, ≈ 870,000 notifications per month (SOLUTION_DESIGN §7.1), container apps billed at the active rate (upper bound), no reservations or savings plans. The figures are an order of magnitude for comparing options, not a quote.

| Service | Sizing | ≈ USD / month |
|---|---|---|
| Container Apps | intake 2 × (0.5 vCPU, 1 GiB) always on; processor 1×; writer 1×; ops API scale-to-zero | 150 |
| Service Bus Standard | base charge + ≈ 8.7 M operations (inside the 13 M included) | 10 |
| PostgreSQL Flexible Server | D2ds_v5 (≈ 130) × 2 for zone-redundant HA, 64 GB storage, backups | 280 |
| Key Vault | < 10,000 operations (secrets are cached) | 1 |
| Application Insights / Log Analytics ingestion | ≈ 10–15 GB/month (sampled requests + business events + diagnostics) | 35 |
| Azure Monitor alerts | 2 metric alerts + 5 log alerts split by 3 countries | 15 |
| VNet, private DNS zone | — | 1 |
| **Total prod** | | **≈ 490** |

| Environment | ≈ USD / month | Main difference |
|---|---|---|
| dev | ≈ 70 | Burstable B1ms DB, 1 intake replica |
| test | ≈ 130 | Burstable B2s DB, 2 intake replicas |
| prod | ≈ 490 | Zone-redundant HA DB, always-warm intake |

**Unit cost**: ≈ USD 0.0006 per notification in prod.

**Biggest levers**:
1. PostgreSQL with HA is about 57 % of prod. Dropping HA saves ≈ 130/month but turns a zone failure into a database outage (notifications would still be accepted and queued).
2. Service Bus Premium would add ≈ 670/month (+137 %) for network isolation; deferred until required by policy.
3. A 1-year reservation on PostgreSQL compute saves ≈ 35–40 % of that line.

**Budgets** are deployed per environment (dev 150, test 250, prod 700 USD), alerting at 80 % of actual spend and at 100 % of forecast.
