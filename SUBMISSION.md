# Submission

> Complete every section. Keep it short — this is the reviewers' entry point.

## Candidate

| Field | Value |
|---|---|
| Candidate ID (provided by your recruiter) | anglotro18@gmail.com |
| Final commit SHA | Sent in the submission email (a commit cannot contain its own SHA). It is the commit that last modified this file. |
| Date and time of submission (with time zone) | 2026-11-20, UTC−5 (Colombia); exact time in the submission email |

## Time spent

Working time measured from the commit history, with an AI coding agent drafting under my direction (see [`AI_USAGE.md`](AI_USAGE.md)). It is below the 6–8 h guide because drafting was AI-assisted; reviewing the material and preparing the defense comes on top.

| Part | Approximate time |
|---|---|
| A. Integration and data design | 0 h 50 min (including reading the scenario and references) |
| B. Integration component | 1 h 20 min |
| C. Azure IaC and CI/CD | 1 h 10 min (including fixing CI on GitHub) |
| D. Operations | 0 h 20 min |
| E. Code review | 0 h 40 min |
| F. Responsible AI | 0 h 15 min |
| **Total** | **≈ 4 h 35 min** |

## Stack

- Practical part (Part B): **Java 21 + Spring Boot 4.1.2** (Spring MVC, Actuator), Maven Wrapper 3.9.9, JUnit + AssertJ + Mockito, networknt JSON Schema validator. Spring Boot 4 instead of 3: a critical Spring MVC CVE has no 3.x fix ([ADR-0002](docs/adr/0002-stack-and-data-store.md), A-13).
- Infrastructure as code: **Bicep** (validated with Bicep CLI 0.49.2: lint, build, build-params for dev/test/prod)
- Pipeline: **GitHub Actions** (CI executed; CD defined, not executed)
- CI run link: https://github.com/AngieloToro/2611-backend-assessment/actions/runs/44851115490 (all jobs green; dependency review runs on pull requests only)

## Where to start reading

1. [`docs/design/SOLUTION_DESIGN.md`](docs/design/SOLUTION_DESIGN.md): the end-to-end design. Sections 5 (idempotency and ordering) and 7 (capacity in numbers) carry the key decisions.
2. [`practical/README.md`](practical/README.md): maps each requirement (FR1, FR2, FR4, NFR3, NFR4, NFR6) to the code and to the tests that prove it.
3. [`docs/operations/INC-0412-RESPONSE.md`](docs/operations/INC-0412-RESPONSE.md) and [`pr-review/REVIEW.md`](pr-review/REVIEW.md): the design principles applied to a real failure and to a teammate's code.

## Known gaps and what I would do next

In priority order:

1. **Processor, Salesforce writer and ops API are designed, not implemented** (out of Part B scope). Next would be the processor (inbox, ordering rule, outbox), because it carries FR4/FR5, followed by the writer with composite upserts, circuit breaker and budget guard.
2. **Service Bus publisher** in `practical/` is a stub that reproduces duplicate detection. The Azure SDK implementation would be tested against the Service Bus emulator.
3. **Austral (Chile) adapter**: designed (contract, mapping, dedup key), not implemented; one more `ProviderAdapter`.
4. **IaC validated statically only**: no ARM preflight or `what-if` without a subscription. Missing pieces: per-secret Key Vault role scope once the secrets exist, a Container Apps job for database migrations, and Service Bus Premium with private endpoints if the security policy requires it.
5. **Supply chain**: only the Trivy action is pinned by commit SHA; all actions should be, with Dependabot, plus image signing (cosign).
6. **Open questions for other teams**, recorded as assumptions: partial refunds in Salesforce (A-5), bulk-safety of the Salesforce flow for 200-record batches (A-9), privacy sign-off on `rawReference` (A-12), provider settlement reports for reconciliation (A-15).
