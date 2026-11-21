# AI usage disclosure

> **Mandatory.** AI assistants are allowed. What we value is how you governed their use.
> If you did not use any AI tool, tick the box below and leave the rest empty.

- [ ] I did not use any AI tool in this assessment.

## Tools

| Tool (name and version, if known) | Where you used it (part and files) | What you used it for |
|---|---|---|
| **Claude Code** (Anthropic, model Claude Opus 5.5), as an agent in VS Code with access to this repository and a local shell | **All parts (A–F)**: `docs/`, `practical/`, `infra/`, `.github/`, `pr-review/REVIEW.md`, this file | Reading the scenario and references; proposing design options and trade-offs; drafting documents, code, tests, Bicep and pipelines; running builds, tests and linters locally; diagnosing CI failures. Every commit it wrote carries a `Co-Authored-By: Claude` trailer, so the history shows its involvement. |

No other AI tool was used.

**How I worked with it.** It was an assisted, not autonomous, process. For each part the assistant first presented options with trade-offs, and **I took the decisions**: Java 21 instead of Mule, Azure Container Apps, Service Bus Standard instead of Premium, PostgreSQL instead of Cosmos DB, asynchronous micro-batches to Salesforce, Bicep, and open-source tooling throughout. The assistant then drafted the deliverables, which I reviewed before moving on.

## Validation

"Me" = I did it myself. "Assistant" = Claude Code ran the check and showed me the result. Automated tools (tests, linters, scanners) are the main safeguard against plausible-but-wrong AI output, which is why every deliverable that can be checked by a tool was.

| Output | How it was validated | Who |
|---|---|---|
| Intake code (Part B) | **110 automated tests**, run locally on JDK 21 (`mvnw clean test`) and in CI. Signatures are checked against the providers' **published test vectors**, byte for byte; every produced event is validated against the JSON Schema contract (`CanonicalEventContractTest`). | **Me** (local run) + CI |
| Signature algorithm | One test vector recomputed independently with `openssl dgst -hmac`, outside the Java code, to confirm the expected value and diagnose the CRLF issue below. | Assistant |
| Tests themselves | Checked that they assert behaviour, not just "no exception": a duplicate must produce exactly one message, a tampered body must be rejected, logs must not contain the samples' personal data. This is the standard applied to the Copilot-generated tests in PR-214 (finding #10). | Assistant, reviewed by me |
| Dependencies and image | Trivy in CI and locally on the JAR and the runtime image: 0 fixable HIGH/CRITICAL vulnerabilities at submission. | CI, **reviewed by me** |
| Bicep (Part C) | `bicep lint`, `bicep build`, `bicep build-params` for dev/test/prod: 0 errors, 0 warnings, checked by the compiler against Azure's resource schemas. Also in CI. | Assistant + CI |
| Pipelines (Part C) | actionlint locally and in CI. I reviewed the GitHub Actions runs, reported each failure back, and re-checked until every CI job was green. | **Me** + CI |
| OpenAPI contract | Redocly CLI lint, 0 warnings; also in CI. | Assistant + CI |
| Design figures (Parts A, D) | Capacity, budget and freshness numbers recomputed (e.g. 116,400 ÷ 200 ≈ 582 calls; 43,120 ≈ 4,312 × 10 in INC-0412; 32 h detection delay). The INC-0412 root cause was checked against the timeline, the Andina changelog and the error codes, ruling out the two other recent changes. | Assistant; I reviewed the reasoning |
| Code review (Part E) | Line numbers checked against the `+` side of the diff; each finding traced to a requirement in `SCENARIO.md` or a reference document. | Assistant; I reviewed the findings |

**Limits I am aware of.** The design documents, the Bicep and the CD pipeline cannot be executed without an Azure subscription, so for them the validation is static (compilers, linters, recomputed figures, review), not runtime. I reviewed all of them and I am responsible for every decision in them; the live defense is where I expect to demonstrate that.

## What I discarded or corrected

Concrete cases where the first output was wrong, or where validation forced a change:

1. **Spring Boot 3.3.5 was vulnerable.** The first version used Spring Boot 3.3.5. The CI Trivy gate found 40 fixable HIGH/CRITICAL CVEs. Upgrading to 3.5.16 still left a critical Spring MVC CVE with no 3.x fix, so the project moved to Spring Boot 4.1.2, and the base image from Debian 12 to Debian 13 distroless. A release a few weeks old was preferred over one published the day before. Justified in ADR-0002 / A-13.
2. **Test vectors failed on Windows.** All signature tests failed at first. The cause was Git's `core.autocrlf` converting the provider samples to CRLF, which changes the signed bytes. Fixed with `.gitattributes`, not by weakening the tests.
3. **Correlation id lost on error responses.** The first controller cleared the logging context in a `finally` block before the exception handler ran, so `401`/`503` responses had no correlation id. Moved to a servlet filter, and a test now asserts it.
4. **Unsupported Bicep property.** Managed identity on KEDA scale rules is not supported by Container Apps API `2024-03-01` (linter warning). Moved to the GA API `2025-01-01` instead of suppressing the warning.
5. **Key Vault firewall.** The first draft allowed all networks. Changed to default-deny with only the Container Apps subnet allowed.
6. **Budget start date.** The first draft defaulted it to "the current month", which would change on every deployment. Made it a fixed parameter.
7. **Pipeline errors found by running it.** The reusable deploy workflow lacked `id-token: write` from its caller. The Maven wrapper was not executable on Linux. The `trivy-action` tag `0.28.0` no longer exists, so it is now pinned by commit SHA. A migration step referenced a job that did not exist in the IaC, so it was removed. actionlint was added to CI so the first kind of error is caught earlier.
8. **Contract-evolution wording.** A draft assumption said a field could be "removed in a minor schema version", which contradicts the versioning rules in `docs/api/README.md`. Corrected to a major version.
9. **Mule as the stack.** The reference stack was considered and discarded deliberately: I can defend Java better, and Mule's tests could not run in public CI without enterprise credentials (ADR-0002).

## Data I shared with AI tools

- The contents of **this repository**: the assessment material, which is fictional by design (Meridian Mutual, providers, people and figures), plus my own work.
- The **fictional sandbox test secrets** published in `reference/` (used by the tests). No real secret, token or credential was shared or created.
- My name and personal email, only to configure the Git author identity of this repository.
- **No** personal data of real people, **no** material from any current or former employer, and **no** proprietary code. The assistant ran in a separate folder (`C:\dev\2611-backend-assessment`), isolated from my work repositories.
