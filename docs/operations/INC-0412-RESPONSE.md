# INC-0412 — Response: Colombian paid policies still suspended

**Summary.** Since Andina Pay's API 2.3 went live (Sat 2026-11-28 23:00 COT), payers' free-text references reach Salesforce un-normalised. Salesforce rejects them with `400 INVALID_FIELD`, v1 retries every rejection 10 times and dead-letters it. About **4,300 Colombian payments** (4,198 approved) were never recorded, so their policies were not reinstated. No alert fired. I would raise the severity to **SEV-1**: customers paid but have no coverage, the month-end peak is in progress, and 28 % of the shared Salesforce API budget is being burnt.

## 1. First 30 minutes

| Min | Check / action | Why |
|---|---|---|
| 0–5 | Acknowledge, open the incident channel, propose **SEV-1**, name a comms lead for the contact center. | Coverage impact on real customers during the peak. |
| 5–10 | **Scope.** Snapshot: only CO is affected. Received volume is normal (37,215 vs 36,480), signature failures are 0, the main queue is empty. **DLQ: 4,312. SF `400`: 43,120 ≈ 4,312 × 10.** | The provider delivers and we accept. Every DLQ message failed 10 times with a `400`, so the failure is between the consumer and Salesforce, and it is permanent rather than transient. |
| 10–15 | **Error detail.** Peek DLQ samples: `INVALID_FIELD … Foreign key external ID: 000215560 not found`. References are `000215560` and `pol-co-000198004`, not `POL-CO-NNNNNNNNN`. | The lookup by `Policy_Number__c` (upper case, prefixed) fails because the reference format changed. |
| 15–20 | **What changed.** First DLQ message at Sat **23:41**. Andina changelog 2.3, effective Sat **23:00**: "`referencia` is now delivered exactly as entered by the payer". v1.14.0 (Fri 18:05) would have failed from Friday; the Salesforce release (Sat 22:00) adds a validation rule on `Policy__c`, which would fail with `FIELD_CUSTOM_VALIDATION_EXCEPTION`, not `INVALID_FIELD`. | Timing and error type single out the Andina change and rule out the other two. |
| 20–25 | **Blast radius and budget.** CO received − upserted = 4,335 ≈ DLQ + in flight, so ≈ 12 % of CO payments are affected. Budget is at 66 % (+28 points ≈ 43 k wasted retries) during the **month-end peak** (Nov 29 – Dec 2, up to 120 k notifications/day). | If retries continue at peak volume, the **org-wide** limit is at risk, which would also block the contact center and partners. |
| 25–30 | **Contain.** Set `payments-co` max delivery count from 10 to 1 (portal, temporary) so data errors stop burning calls. Create DLQ-depth alerts for `payments-co` and `payments-cl`. Tell the Salesforce team about the budget, and give the contact center an ETA and a script. | Stop the damage before fixing; the existing alert covered only Mexico. |

## 2. Most likely root cause

**Andina API 2.3 changed the semantics of `transaccion.referencia`** (no longer normalised by Andina). v1 maps it verbatim to `Policy__r.Policy_Number__c`. Payers who edited or retyped the pre-filled reference produced values like `000215560` or `pol-co-000198004`, for which no policy exists, and Salesforce answers `400 INVALID_FIELD`. **Contributing factors:** (a) v1 treats a permanent data error like a transient one (immediate redelivery × 10, reason `MaxDeliveryCountExceeded`); (b) a DLQ alert existed for Mexico only; (c) the provider changelog was not reviewed before its effective date.

**Confirm** with read-only checks:
1. Classify every DLQ message by reference: expect ≈ 100 % not matching `^POL-CO-\d{9}$`, and every recorded CO payment since 23:00 matching it.
2. Normalise a sample of 50 DLQ references (trim, upper-case, pad digits to 9, add `POL-CO-`) and query `Policy__c` in batches: expect almost all to exist.
3. Confirm that no DLQ message was enqueued before Sat 23:00, and confirm the change with Andina's support.

## 3. Remediation

1. **Hotfix v1.14.1** (expedited review, deployed by the normal pipeline): normalise the reference in the CO mapper (the rule in `PolicyReferenceNormalizer`, with the three DLQ examples as tests). References that cannot be resolved go to a separate "unresolved" DLQ with their reason instead of being retried. Restore max delivery count after the deploy.
2. **Reprocess the DLQ safely.** The DLQ contains several states of the same transaction (`TRX-CO-5603318` is both `APROBADA` and `REVERSADA`), and v1 writes each message unconditionally, so a naive replay could leave a reversed payment as `Captured` and reinstate coverage for money that was returned.
   - **Snapshot**: peek (not receive) all 4,312 messages to a file; counts by `estado` and reference pattern.
   - **Collapse per `transaccion.id`**: keep only the **latest state**, by precedence `PENDIENTE < APROBADA/RECHAZADA < REVERSADA` and then `fecha_transaccion`. Andina retries carry new `notificacion_id`s, so deduplicate by `transaccion.id + estado`.
   - **Dry run**: normalise references and check that policies exist (≈ 22 calls of 200 ids). Split into *resolvable* and *unresolvable*; the unresolvable list goes to the contact center to confirm the policy with the customer.
   - **Replay** the resolvable set through the fixed consumer in batches (≈ 200 per minute), **approved payments first** (they reinstate coverage), then rejected and reversed. Upserts by `Payment_Key__c` make a repeated replay harmless. Stop automatically if the error rate exceeds 1 %. Cost ≈ 4.3 k calls (≈ 3 % of the budget), or ≈ 22 with composite upserts.
   - **Verify**: DLQ drained; CO received ≈ recorded; the 37 reported policies are `Active` (reinstated by the Salesforce flow, which the integration never writes); reconcile with Andina's settlement report for Nov 28–30.
3. **Close the loop**: send the contact center the list of reinstated customers. Post-incident review within 5 business days, blameless, with owners and dates for section 4.

## 4. Prevention

| What | Would have… |
|---|---|
| DLQ and dead-letter alerts for **every** country, defined in IaC (`monitoring.bicep`) | Paged on-call at 23:41 Saturday instead of customers reporting on Monday (≈ 32 h earlier). |
| Error classification: data errors dead-lettered at first failure with the real reason; backoff instead of immediate redelivery (ADR-0003) | Saved ≈ 39 k Salesforce calls and made the reason visible (`SF_DATA_ERROR:INVALID_FIELD`). |
| Reference normalisation in each provider adapter, with unresolvable references parked for operations (Part B) | Avoided the incident for every reference that can be normalised. |
| **Provider change management**: subscribe to the changelogs, review them in the squad's weekly sync, test in the provider sandbox before the effective date, and avoid effective dates inside the month-end peak | Caught the 2.3 change before Saturday. |
| Per-country SLO alerts (freshness, success ratio received vs recorded) and the hourly reconciliation (`OPERATIONS.md`) | Detected the 12 % drop in CO within the hour, even without a DLQ. |
| A tested **replay tool** (dry run, collapse per transaction, ordered, rate-limited) in the runbook | Turned remediation into a routine procedure instead of ad-hoc scripting during an incident. |
