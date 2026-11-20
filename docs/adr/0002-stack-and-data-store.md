# ADR-0002: Implementation stack and data store

- **Status:** Accepted
- **Date:** 2026-11-20

## Context

We need (a) a runtime for the intake, processor, Salesforce writer and ops API, and (b) a store for the state described in the solution design: an inbox for deduplication, the latest state per payment for ordering, an outbox, and the processing log that operations searches (FR4, FR5, FR7).

Forces:

- **NFR4**: Andina's 5 s timeout; the intake must answer well under a second, also after scaling out.
- **NFR1**: bursts of 40 /s, quiet nights; scale from 2 to N without manual work.
- **NFR6**: managed identities, secrets in Key Vault, no payer data in logs.
- **NFR8**: cost proportional to a modest volume (≈ 870 k notifications per month).
- **NFR9**: provider-specific logic isolated and testable.
- The platform offers a Mule 4 runtime and can also front services on Azure. The reference stack is MuleSoft; Java 21 is accepted with justification.
- Reviewers read the code; tests must be runnable without enterprise credentials.

## Options considered

### Runtime

#### Option R1 — Mule 4 applications on the integration platform
- Pros: the reference stack; DataWeave is excellent for mapping; native connectors for Service Bus and Salesforce; the platform team already operates it.
- Cons: ordering and dedup need a transactional store anyway, which Mule does not provide; the Salesforce and Service Bus connectors are enterprise-licensed; per-vCore licensing cost; MUnit and enterprise dependencies need credentials, so CI cannot be shown publicly; less fine-grained control of transactions (inbox + outbox in one DB transaction).

#### Option R2 — Java 21 + Spring Boot 3 on Azure Container Apps
- Pros: full control of signature verification on raw bytes, DB transactions and the outbox; open-source stack with no licences; always-warm replicas (no cold start against the 5 s timeout); KEDA scaling on HTTP concurrency and on queue length; managed identity everywhere; JUnit 5 tests run anywhere; the team's Java prototype shows existing Java skills.
- Cons: more code to write and own than DataWeave mappings; the squad runs a service outside the integration platform's runtime (it is still fronted by the API Manager, as the scenario allows).

#### Option R3 — Java 21 on Azure Functions (Flex Consumption)
- Pros: cheapest at low volume; Service Bus triggers built in.
- Cons: cold starts risk the 5 s timeout unless always-ready instances are paid for; batching and circuit-breaking a writer is awkward in a function model; less control over concurrency against Salesforce's 25-request limit.

### Data store

#### Option D1 — Azure Database for PostgreSQL Flexible Server
- Pros: `UNIQUE` constraint + `SELECT … FOR UPDATE` make dedup and ordering atomic and simple to reason about; inbox, state and outbox in **one transaction**; operations can query by any identifier with ordinary indexes (FR7); open-source engine, portable; Entra ID authentication; VNet integration available on every tier; built-in PITR backups.
- Cons: a server to size (no pure serverless); zone-redundant HA doubles compute cost; we own schema migrations (Flyway).

#### Option D2 — Azure Cosmos DB for NoSQL
- Pros: serverless or autoscale pricing; TTL for retention; unlimited scale.
- Cons: transactional batches only within one logical partition; FR7 lookups by several identifiers become cross-partition queries or need extra containers; the outbox pattern needs the change feed; overkill for ≈ 12 GB/year.

#### Option D3 — Azure SQL Database
- Pros: equivalent relational guarantees to D1; serverless tier.
- Cons: proprietary engine; serverless auto-pause adds resume latency; no advantage over PostgreSQL for this workload.

#### Option D4 — No store (rely on Service Bus duplicate detection and Salesforce upsert)
- Pros: least infrastructure.
- Cons: duplicate detection is a time window, not a guarantee; FR5 cannot be met without knowing the current state (every event would need a Salesforce read, costing API budget); FR7 would require searching logs. **Rejected.**

## Decision

- **Runtime: Java 21 + Spring Boot 3 on Azure Container Apps (R2)**, four small apps built from one repository: `payments-intake`, `payments-processor`, `payments-sf-writer`, `payments-ops-api`. All public traffic goes through the API Manager.
- **Store: Azure Database for PostgreSQL Flexible Server (D1)**, General Purpose 2 vCores in prod with zone-redundant HA, Burstable in dev/test.

The deciding factor is that FR4 and FR5 require **atomic "check, change state, record outbox"** semantics. A relational store gives that with one transaction, and Java/Spring makes that transaction explicit and testable. Mule would still need this store, plus licences, and its tests could not run in a public CI.

## Consequences

- Positive:
  - Idempotency and ordering live in one tested place; consumers and Salesforce receive already-resolved state.
  - Entire stack is open source (OpenJDK, Spring Boot, PostgreSQL); no runtime licences.
  - Tests (JUnit 5) run in CI without credentials, using the providers' test vectors.
- Negative:
  - The squad owns more code than with DataWeave mappings, plus a database (migrations, upgrades, capacity).
  - Two runtimes in the organisation (Mule platform and Container Apps): platform teams must support monitoring for both.
  - Container Apps ingress must be reachable only from the API Manager; that network path needs work with the platform team (A-2).
- Risks and how they are mitigated:
  - *PostgreSQL becomes a single point of failure* → zone-redundant HA in prod; intake does not depend on it (it only enqueues), so webhooks are still accepted during a DB failover.
  - *Mapping code drifts from provider specs* → adapter tests run against the published samples and signature test vectors on every pull request.
  - *Skills mismatch with the integration platform team* → the adapter interface keeps mappings small and documented; a future move of the intake to Mule is possible because the contracts (OpenAPI, event schema) are stack-independent.
