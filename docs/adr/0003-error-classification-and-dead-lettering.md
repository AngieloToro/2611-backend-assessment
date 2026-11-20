# ADR-0003: Error classification and dead-lettering

- **Status:** Accepted
- **Date:** 2026-11-20

## Context

Every accepted notification must eventually reach Salesforce or a place where a human can see and act on it (NFR3, FR7). Errors in this flow are very different in nature:

- **Transient and systemic**: Salesforce outages (≈ 3 % of the time), timeouts, `5xx`.
- **Transient and per record**: `UNABLE_TO_LOCK_ROW`.
- **Permanent, data**: `INVALID_FIELD` (e.g. policy not found), validation rules, unresolvable references.
- **Shared-limit**: `REQUEST_LIMIT_EXCEEDED`, which blocks every integration of the org.

A uniform policy — "abandon and let the broker redeliver up to N times" — treats them all alike. INC-0412 shows the effect: data errors were retried 10 times each, immediately, burning ≈ 43,000 Salesforce calls, reaching 66 % of the budget, and dead-lettering 4,312 payments with the non-informative reason `MaxDeliveryCountExceeded`, with no alert for Colombia.

## Options considered

### Option 1 — Broker redelivery only (max delivery count, immediate redelivery)
- Pros: no code; default behaviour of Service Bus.
- Cons: retries permanent errors; immediate redelivery does not give an outage time to pass; every retry costs an API call; DLQ reason hides the real cause.

### Option 2 — Classify errors in code, with a different action per class
- Pros: permanent errors stop at the first attempt and keep the real reason; systemic errors pause consumption instead of consuming retries; per-record transient errors get real backoff.
- Cons: classification code must be maintained and tested; a misclassification (e.g. treating a transient error as permanent) sends messages to the DLQ that would have succeeded.

### Option 3 — Separate retry queues with fixed delays (retry-1m, retry-10m, retry-1h)
- Pros: explicit and visible backoff.
- Cons: more infrastructure; still needs classification to avoid retrying permanent errors; scheduled messages on the same entity achieve the same with less.

## Decision

**Option 2.** Each failure is classified and handled as follows (details in SOLUTION_DESIGN §6):

| Class | Action | DLQ reason |
|---|---|---|
| Systemic transient (`5xx`, timeouts) | Circuit breaker: stop receiving, probe every 60 s | — (messages wait) |
| Per-record transient (`UNABLE_TO_LOCK_ROW`) | Re-schedule with exponential backoff, max 5 | `SF_TRANSIENT_EXHAUSTED` |
| Permanent data (`400` validation/field errors, `UNRESOLVED_REFERENCE`) | Dead-letter at first occurrence | `SF_DATA_ERROR:<code>` / `UNRESOLVED_REFERENCE` |
| Shared limit (`403 REQUEST_LIMIT_EXCEEDED`, budget guard ≥ 80 %) | Stop all calls for 15 min, page | — |
| Unknown | Treated as per-record transient (safe default), counted | `UNCLASSIFIED` |

Every dead-lettered message carries the reason, the Salesforce error code, the payment key and the correlation id as application properties. Every DLQ (inbound queue and each subscription) has an alert in **every** country.

## Consequences

- Positive:
  - A wave of data errors costs one API call per record (as part of a batch), not ten.
  - Operations sees *why* a message failed without opening logs.
  - The shared org limit is protected even if our own logic misbehaves.
- Negative:
  - Classification is code we must test; new Salesforce error codes need a review.
  - A permanent error that is actually caused by a temporary state in Salesforce (e.g. a policy created a few seconds later) goes to the DLQ and needs a replay.
- Risks and how they are mitigated:
  - *Mass dead-lettering after a bad deploy or a provider change* → alert on DLQ growth rate per country, and a safe, idempotent replay procedure.
  - *Misclassification* → unknown codes default to retryable, and the reconciliation job catches anything not synced after 1 h.
