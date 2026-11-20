# ADR-0001: Integration with Salesforce

- **Status:** Accepted
- **Date:** 2026-11-20

## Context

Every payment must end up as a `Premium_Payment__c` in Salesforce (FR3), exactly once in business terms (FR4), never contradicting the provider's latest state (FR5), and 95 % of them within 5 minutes (NFR2). Salesforce constraints (NFR5, [`API_CONSTRAINTS.md`](../../reference/salesforce/API_CONSTRAINTS.md)):

- ≈ 97 % availability, outages of 5–90 minutes (≈ 22 h per month).
- 150,000 API calls per rolling 24 h for this integration, inside an **org-wide** limit shared with the contact center and partners; exceeding it blocks **every** integration with `403 REQUEST_LIMIT_EXCEEDED`.
- At most 25 concurrent requests from the integration user.
- Single-record upsert p95 1.2 s (up to 8 s under load); 200-record composite upsert p95 4 s, and it counts as **one** call.

Peak load is 120,000 notifications per day with bursts of 40 per second for 15 minutes (NFR1). Providers time out after 5–10 seconds (NFR4).

## Options considered

### Option 1 — Synchronous, per record, inside the webhook request
- Pros: simplest; the provider gets an error if Salesforce fails, so the provider's retry acts as our buffer.
- Cons: a Salesforce outage turns into webhook failures and provider retry storms; Andina's 5 s timeout is shorter than Salesforce's 8 s worst-case latency; 40 /s × 1.2 s ≈ 48 concurrent calls (limit 25); ≈ 116,000 calls on a peak day (78 % of the budget before any retry). Durability depends on the provider's retry policy (Andina gives up after 5 attempts in 24 h). **Rejected.**

### Option 2 — Asynchronous, one upsert per message
- Pros: decouples the provider from Salesforce; simple consumer; per-record error handling is trivial.
- Cons: same call count and concurrency problem as option 1 at peak; each outage causes per-message retries that burn calls (this is how v1 reached 66 % of the budget in INC-0412).

### Option 3 — Asynchronous, micro-batches with the composite upsert API (up to 200 records)
- Pros: calls bounded by flush frequency, not by volume (≤ 600 calls on a peak day, worst case 34,560 with two replicas flushing every 5 s); concurrency ≤ 2; latency still in seconds; `allOrNone: false` keeps per-record outcomes.
- Cons: partial-failure handling per record; a batch waits up to 5 s; a batch is one unit for transient errors.

### Option 4 — Bulk API 2.0 jobs
- Pros: very high throughput, minimal calls.
- Cons: asynchronous jobs measured in minutes, job polling, harder per-record error mapping; designed for loads far larger than ours. Endangers NFR2 for little benefit.

### Option 5 — Salesforce pulls (Platform Events / external objects, or Salesforce consuming our topic)
- Pros: Salesforce owns its writes.
- Cons: moves our integration logic into an org we do not own; Platform Event allocations are also limited and shared; the Salesforce team would have to build and operate it.

## Decision

**Option 3: asynchronous, micro-batched upserts via the composite sObject collection API, keyed by `Payment_Key__c`**, executed by a dedicated `payments-sf-writer` subscribed to the canonical event topic.

Rules that protect the shared budget and live with 97 % availability:

1. **Flush** when 200 records are buffered or 5 s have passed; max **2 replicas**, **1 in-flight call per replica**.
2. **Write the latest state, not the event**: the writer reads `payment_state` for the batch's payment keys and deduplicates within the batch, so any order of arrival converges to the provider's latest state (FR5) and replays are harmless.
3. **Circuit breaker** on `5xx`/timeouts: stop receiving instead of retrying; messages wait in the subscription without burning their delivery count; half-open probe every 60 s.
4. **Never retry data errors** (`INVALID_FIELD`, validation errors): dead-letter immediately with the Salesforce error code.
5. **Budget guard**: track usage from the `Sforce-Limit-Info` header and a rolling counter; halt at 80 % of 150,000 and page; on `REQUEST_LIMIT_EXCEEDED` stop for 15 min.
6. **Token caching**: one OAuth token reused for its ≈ 2 h lifetime, refreshed on `401`.
7. **Never write `Policy__c`**: the payload links by `Policy__r.Policy_Number__c` only; reinstatement stays with Salesforce automation.

## Consequences

- Positive:
  - Salesforce outages no longer affect webhook acknowledgement or providers.
  - API usage is bounded and small (< 1 % of the budget on a realistic peak day), protecting the other integrations of the org.
  - Ordering problems are solved once, in our store, instead of in every consumer.
- Negative:
  - Salesforce becomes eventually consistent with the providers (seconds normally, the outage duration otherwise).
  - We must keep a state store (`payment_state`) that mirrors part of Salesforce — more infrastructure and a reconciliation job (see ADR-0002).
  - Batch partial failures need careful per-record handling and tests.
  - A record-triggered flow runs over up to 200 records in one transaction: the Salesforce team must confirm the flow is bulk-safe (A-9).
- Risks and how they are mitigated:
  - *Long outages break the 99.5 % / 1 h freshness target* → measured and reported separately; the risk is shared with the Salesforce team (see SOLUTION_DESIGN §7.4).
  - *Writer bug writes a wrong status, affecting coverage* → contract tests with the Salesforce data model, status mapping covered by unit tests, canary in test environment against a Salesforce sandbox.
  - *Stuck messages go unnoticed* → hourly reconciliation of `sf_synced_version < version`, DLQ alerts for every country.
