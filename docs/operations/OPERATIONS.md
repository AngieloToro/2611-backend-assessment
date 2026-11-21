# Operations — Premium Payment Notifications

## 1. SLIs and SLOs (per country, 28-day rolling window)

Every SLI is computed **separately for MX, CO and CL** from the business events the services emit (section 2), never from raw logs.

| # | SLI (good events / valid events) | SLO | Source requirement |
|---|---|---|---|
| S1 | **Freshness 5 min**: accepted notifications recorded in Salesforce ≤ 5 min after acceptance / accepted notifications that must reach Salesforce | **95 %** | NFR2 |
| S2 | **Freshness 1 h**: same, ≤ 1 h | **99.5 %** | NFR2 |
| S3 | **Durability**: accepted notifications that reach a *known* terminal state (`RECORDED`, `DUPLICATE`, `SUPERSEDED`, or `DEAD_LETTERED` with a reason and an owner) within 24 h / accepted notifications | **100 %**; any loss is an incident | NFR3 |
| S4 | **Acceptance**: webhook requests answered `2xx` within 2 s / authentic, well-formed requests | **99.9 %** | NFR3, NFR4 |

- **Accepted** means the intake answered `2xx` (the queue holds the message). Rejections for invalid signatures or payloads are excluded from S4: they are correct behaviour, and they have their own alerts.
- **Error budgets per month** (≈ 870 k notifications: MX 391 k, CO 348 k, CL 130 k): S1 allows ≈ 19.6 k / 17.4 k / 6.5 k late notifications, S2 ≈ 1,960 / 1,740 / 650, S3 allows **zero**.
- **Salesforce unavailability** (≈ 3 %, see SOLUTION_DESIGN §7.4) consumes S1/S2 budget. The dashboard shows each SLI twice, *total* and *excluding minutes with the Salesforce circuit open*, so we can tell our own regressions from Salesforce outages and take the data to the Salesforce team.
- **Loss is measured, not assumed.** An hourly reconciliation compares accepted notifications with `payment_state` (`sf_synced_version < version` for more than 1 h = loss risk). A daily reconciliation compares each provider's settlement report with Salesforce (assumption A-15).

## 2. Instrumentation

**Correlation id, end to end (NFR7).**

```
API Manager X-Correlation-Id ─► intake (MDC, CloudEvents correlationid, Service Bus CorrelationId)
  ─► processor (MDC from message) ─► notification.correlation_id (DB) ─► outbox ─► topic message
  ─► sf-writer (MDC per record) ─► ops API (searchable by correlation id or any provider id)
```

The OpenTelemetry Java agent (Application Insights) also propagates W3C `traceparent` over HTTP and Service Bus, so one end-to-end trace shows intake → queue → processor → topic → writer → Salesforce call. Every log line and business event carries `correlationId`, `provider`, `country`, `paymentKey` and `providerEventId`.

**What we emit.**

| Type | Name | Dimensions | Used for |
|---|---|---|---|
| Counter | `payments.intake.notifications` | provider, country, outcome (`accepted`, `duplicate`, `ignored`, `invalid_signature`, `invalid_payload`, `not_accepted`) | S4, signature and no-traffic alerts |
| Histogram | `http.server.requests` (intake) | provider, status | Latency against provider timeouts |
| Counter | `payments.processor.outcomes` | country, outcome (`applied`, `duplicate`, `superseded`, `failed`) | FR4/FR5 behaviour |
| Event | `PaymentRecorded` | country, status, `freshnessSeconds`, batchSize | **S1, S2** |
| Event | `NotificationDeadLettered` | country, stage, reason, Salesforce error code | **S3**, DLQ alerts per country |
| Gauge | `payments.reconciliation.unsynced` | country | **S3** loss risk |
| Gauge | `payments.salesforce.budget.used.percent`, `payments.salesforce.circuit.open` | — | NFR5 |
| Counter | `payments.salesforce.responses` | country, status, errorCode | 4xx/5xx trends |

Logs are structured JSON. Business events are **never sampled**; request telemetry uses adaptive sampling.

**What we must never log or send to telemetry.** Payer names, national IDs (CC, RUT, CURP), emails and phone numbers; payment tokens; request or message **bodies**; raw headers (they carry signatures); webhook secrets, OAuth tokens and Salesforce session ids; the free-text payment reference (only its resolution: `EXACT`, `NORMALISED`, `UNRESOLVED`). This is enforced by design (personal data is dropped in the intake adapter, before anything is logged or enqueued) and by an automated test that captures log output and fails on personal data.

## 3. Alerts

All alerts are defined in [`infra/modules/monitoring.bicep`](../../infra/modules/monitoring.bicep) and cover **every country** (a country is a query dimension, not a separate alert).

| Alert | Condition | Severity | Who |
|---|---|---|---|
| Notifications not accepted | > 1 % of webhook requests answered `5xx` in 10 min, per provider | **Sev 1** | **Page** payments on-call |
| Dead-lettered by country | ≥ 1 `NotificationDeadLettered` in 15 min (reason and error code in the alert) | **Sev 1** | **Page** on-call |
| Loss risk | `unsynced` > 0 for 1 h, per country | **Sev 1** | **Page** on-call |
| DLQ not empty (platform metric) | `DeadletteredMessages` > 0 on queue or topic | **Sev 1** | **Page** on-call (backstop if the app event is missing) |
| Freshness | p95 freshness > 5 min over 15 min, per country | Sev 2 | On-call (business hours page, otherwise ticket); Salesforce team informed if the circuit is open |
| Backlog growing | > 5,000 active messages for 15 min | Sev 2 | On-call |
| No traffic | 0 accepted notifications for a country in 30 min | Sev 2 | On-call (provider or route outage) |
| Signature failures | > 20 in 10 min, per provider | Sev 2 | On-call + security contact (rotation issue or attack) |
| Salesforce budget | > 70 % of 150 k calls / 24 h (writer halts at 80 %) | Sev 2 | On-call + Salesforce team |
| Cost budget | 80 % actual / 100 % forecast of the monthly budget | Sev 4 | Squad lead (email) |

Sev 1 pages 24×7. Sev 2 pages during business hours (07:00–20:00 in each country's time zone) and opens a ticket otherwise. Every alert links to a runbook entry: what to check first, how to contain, and how to replay safely (same procedure as `INC-0412-RESPONSE.md` §3).
