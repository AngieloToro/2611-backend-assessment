# Practical part — Payment notification intake (MX, CO)

Intake of payment notifications for **Quetzal Pagos (Mexico)** and **Andina Pay (Colombia)**: from the webhook request to the canonical event published on a queue. It implements the `payments-intake` component of the [solution design](../docs/design/SOLUTION_DESIGN.md) and conforms to the [webhook OpenAPI contract](../docs/api/webhooks.openapi.yaml) and the [canonical event schema](../docs/api/events/premium-payment.schema.json).

## Stack and versions

| Item | Version | Why |
|---|---|---|
| Java | 21 (LTS) | Records, sealed interfaces, pattern matching, virtual threads |
| Spring Boot | 4.1.2 (Spring MVC, Actuator; Spring Framework 7, Jackson 3, Tomcat 11) | HTTP endpoint, configuration binding, health probes, Micrometer metrics |
| Maven | 3.9.9 through the Maven Wrapper (`mvnw`) | No local Maven installation needed |
| JUnit, AssertJ, Mockito | managed by Spring Boot | Unit and integration tests |
| networknt json-schema-validator | 1.5.9 (test only) | Validates produced events against the JSON Schema contract |
| Runtime image | `gcr.io/distroless/java21-debian13:nonroot` | No shell or package manager, non-root |

**Why Spring Boot 4 instead of 3**: the CI vulnerability scan found fixable HIGH/CRITICAL CVEs in the Spring Boot 3 dependency tree, including a critical Spring MVC issue fixed only in Spring Framework 7. Details in [ADR-0002](../docs/adr/0002-stack-and-data-store.md). Trivy result at submission: **0 fixable HIGH/CRITICAL** in the JAR and in the base image.

The choice of Java instead of Mule is justified in [ADR-0002](../docs/adr/0002-stack-and-data-store.md). Every dependency is open source; no enterprise credentials are needed to build or test.

## Build and run the tests

Requires a JDK 21 (`JAVA_HOME` pointing to it). Run from this folder (the tests read the provider samples from `../reference/` and the schema from `../docs/`):

```bash
./mvnw test          # Linux / macOS / Git Bash
mvnw.cmd test        # Windows cmd / PowerShell
```

If several JDKs are installed, select JDK 21 for the current shell first, e.g. in PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.13.102-hotspot"   # adjust to your JDK 21 path
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd clean test
```

The build enforces JDK 21 (`maven-enforcer-plugin`) and fails with an explicit message on older JDKs.

Result at the time of submission: **110 tests, 0 failures**.

Run locally (secrets are provided as environment variables, here the providers' sandbox secrets):

```bash
PAYMENTS_INTAKE_PROVIDERS_QUETZAL_SECRETS_0=qz_test_whsec_4f8a1c2e9b7d \
PAYMENTS_INTAKE_PROVIDERS_ANDINA_SECRETS_0=andina_test_secret_7c3e19ab \
./mvnw spring-boot:run
# POST http://localhost:8080/payments/webhooks/v1/{quetzal|andina}
```

> **Raw bytes matter.** Signatures are computed over the exact bytes of the samples (LF line endings, no trailing newline). The repository's `.gitattributes` marks `reference/providers/**/samples/*` as binary so that Git on Windows (`core.autocrlf=true`) does not convert them to CRLF, which would break every test vector. This was found while running the tests on Windows.

## How a request flows

```
POST /payments/webhooks/v1/{provider}
  CorrelationIdFilter        X-Correlation-Id from the API Manager (if a UUID) or a new one; MDC + response header
  WebhookController          raw body as byte[] (never re-serialised), 64 KB limit -> 413
  IntakeService              shared flow, the same for every provider
    ProviderRegistry           path segment -> ProviderAdapter            (unknown -> 404)
    adapter.verifySignature    HMAC over raw bytes, constant time, tolerance  (fail -> 401)
    adapter.map                provider payload -> canonical event, PII dropped (bad -> 400, unknown type -> 200 IGNORED)
    publish with deadline      MessageId = dedup key, wait max 2 s          (fail/timeout -> 503 + Retry-After)
  200 ACCEPTED               only after the broker acknowledged the message (NFR3)
```

## Project structure

| Package | Responsibility |
|---|---|
| `web` | HTTP endpoint, correlation id filter, RFC 9457 problem responses (never echo request data) |
| `service` | `IntakeService`: the shared flow, metrics, logging policy, publish deadline |
| `provider` | `ProviderAdapter` interface, registry, HMAC helper, tolerant JSON reader |
| `provider.quetzal` | `QuetzalAdapter`: `t=…,v1=…` hex signature, 300 s tolerance, rotation (several `v1`), minor units |
| `provider.andina` | `AndinaAdapter`: Base64 signature over `timestamp.body`, 600 s tolerance, dedup by `transaccion.id + estado` |
| `domain` | Canonical event (CloudEvents), `Money`, statuses, `PolicyReferenceNormalizer`, deterministic UUID v5 ids |
| `messaging` | `EventPublisher` port and the in-memory Service Bus stub |
| `secrets` | `WebhookSecretProvider` port, current + previous secret for rotation |

Adding a provider means adding one `ProviderAdapter` bean and its tests; nothing else changes (NFR9).

## Requirements covered and where they are tested

| Requirement | How | Tests |
|---|---|---|
| **FR1** Authenticity | HMAC-SHA256 over raw bytes exactly as each spec says; constant-time compare; timestamp tolerance (300 s / 600 s); secret rotation | `QuetzalAdapterTest.Signature`, `AndinaAdapterTest.Signature`: every published test vector, tampered body, re-serialised JSON, wrong secret, signed timestamp changed, skew edges, malformed headers, rotation |
| **FR2** Canonical event | Adapters map states, amounts (minor/major units, never floats), currency case, UTC timestamps, payment method, references | `*AdapterTest.Mapping`, `DomainTest`; `CanonicalEventContractTest` validates **every** produced event against the JSON Schema |
| **FR4** Exactly once (intake share) | Dedup key per provider (Andina: `transaccion.id + estado`, because `notificacion_id` changes per retry); deterministic event id (UUID v5); `MessageId = dedup key` for broker duplicate detection | `retryWithANewNotificationIdIsTheSameBusinessEvent`, `andinaRetry…EnqueuedOnce`, `quetzalRedelivery…EnqueuedOnce`, `InMemoryEventPublisherTest` |
| **NFR3** No loss once accepted | `200` only after the broker acknowledged; any failure → `503` so the provider retries | `answers503WhenTheBrokerFailsSoTheProviderRetries`, `rejectsAnInvalidSignatureAndEnqueuesNothing` |
| **NFR4** Provider timeouts | Publish deadline 2 s (Andina times out at 5 s), virtual threads, no I/O besides the broker | `answers503WithinTheDeadlineWhenTheBrokerIsSlow` |
| **NFR6** Security and privacy | Payer data never read into the event; logs carry identifiers only; secrets from environment (Key Vault references in Azure); generic error details | `neverCarriesPayerPersonalData…`, `neverWritesPayerPersonalDataToTheLogs`, schema rejects extra fields |
| FR5 (intake share) | Out-of-order notifications are all enqueued with provider time and status; the processor resolves order | `outOfOrderNotificationsAreAllEnqueued…` |
| INC-0412 lesson | Andina 2.3 references typed by the payer are normalised (`000215560` → `POL-CO-000215560`); unresolvable ones are still accepted and flagged `UNRESOLVED` | `normalisesReferencesTypedByThePayer`, `DomainTest.PolicyReferences` |

## What is stubbed and how

| Dependency | Stub | Production replacement |
|---|---|---|
| Azure Service Bus (inbound queue) | `InMemoryEventPublisher`: keeps messages in memory and reproduces **duplicate detection by `MessageId` within a 7-day window** | A `ServiceBusEventPublisher` implementing the same `EventPublisher` interface with the Azure SDK (`ServiceBusSenderClient`, `DefaultAzureCredential` = managed identity, role *Azure Service Bus Data Sender* on the queue only) |
| Key Vault | `ConfiguredWebhookSecretProvider` reads secrets from configuration properties bound from environment variables; tests use the sandbox secrets in `application-test.yml` | Same class: in Container Apps the environment variables are **Key Vault references** resolved with the app's managed identity, so no SDK code and no secret in files |
| Clock | `Clock` bean; tests use `MutableClock` to sit inside or outside the signature tolerance of each published vector | `Clock.systemUTC()` |

## Known gaps and what I would do next

1. **Service Bus publisher**: implement `ServiceBusEventPublisher` and an integration test against the Service Bus emulator (Docker).
2. **Austral Pagos (Chile)** adapter: designed (OpenAPI path, mapping table in the design) but out of Part B scope. It is one more `ProviderAdapter` (hex HMAC of the raw body, no timestamp, dedup by `eventId`).
3. **Processor, Salesforce writer and ops API** are designed in Part A, not implemented (out of scope).
4. **Metrics** are counters per provider, country and outcome (`payments.intake.notifications`); latency histograms and OpenTelemetry trace export to Application Insights are configuration work still to do.
5. **Security hardening**: per-provider rate limiting is delegated to the API Manager; a replay cache for Quetzal/Andina within the tolerance window is unnecessary because duplicates are idempotent, but would reduce noise.
6. **Container image**: a `Dockerfile` (or `spring-boot:build-image`) and a non-root distroless base image for Container Apps.
