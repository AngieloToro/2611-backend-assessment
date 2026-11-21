# AI assistant guidelines — Payments Integration squad

> Proposal. Principle: **AI speeds us up, it does not take responsibility.** The author who merges owns the code, whoever (or whatever) typed it.

## 1. What is allowed

Use the **company-approved assistants** (enterprise tenants with no training on our data, data kept in approved regions, audit logs). Good uses:

- Drafting code, tests, IaC, pipelines and documentation; refactoring; explaining unfamiliar code or provider specs.
- Generating test cases from **published** provider samples and test vectors.
- First-pass reviews and checklists (they complement human review, they never replace it).
- Analysing incidents using **sanitised** data (identifiers and error codes, not payloads).

Not allowed: personal accounts or unapproved tools (including browser extensions and plugins), and agents with write access to shared environments (Azure, Salesforce, Service Bus, production GitHub settings).

## 2. Data that must never be shared with an AI tool

| Never share | Use instead |
|---|---|
| Secrets: webhook secrets, Salesforce client secret, tokens, connection strings, `.env` files | Placeholders (`<SECRET>`) |
| **Payer personal data**: names, national IDs (CC, RUT, CURP), emails, phones, addresses | Synthetic data, or the fictional samples in `reference/` |
| Real webhook payloads, DLQ messages, production logs and traces, Salesforce records or exports | Canonical events with identifiers only, redacted with the approved script |
| Payment tokens or anything card-related | — |
| Provider contracts, pricing and security assessments; internal vulnerability details before they are fixed | A paraphrased problem description |

When in doubt, don't paste: ask the squad lead or the security champion.

## 3. Validating AI-generated code and tests before merge

The same bar as any code, plus checks specific to AI output:

1. **The author can explain every line** in the review. "The assistant wrote it" is not an answer.
2. **Tests must prove behaviour.** They must fail if the behaviour breaks: assert outputs and side effects (e.g. `never()` for a duplicate, not `atMost(1)`), and cover the failure paths that matter here (invalid signature, duplicate, out-of-order, broker down, Salesforce `4xx`/`5xx`). A test that only "does not throw" is rejected. For risky logic, break the code on purpose and check that a test goes red.
3. **Verify against the source of truth.** Provider specs and test vectors, the Salesforce data model, and official docs for APIs and versions. Assistants invent methods, properties and versions with confidence: Bicep lint, compilers and contract tests catch many of these, reviewers catch the rest.
4. **Security-sensitive code gets extra care**: signature verification, crypto, authentication, secret handling and personal data. Mandatory second reviewer from the squad; no AI-suggested "temporary" shortcuts (hard-coded secrets, skipped verification).
5. **All CI gates apply unchanged**: tests, contract and IaC lint, secret scanning, CodeQL, dependency and image scans. Never weaken a gate to make AI output pass.
6. **Dependencies suggested by AI** are checked for existence, maintenance, licence and vulnerabilities (typosquatting risk) before they are added.

## 4. Disclosure in pull requests

- The PR template has an **"AI assistance"** section: tool, which files or parts, and **how the output was validated**. "None" is a valid answer.
- Commits substantially written by an agent carry a `Co-Authored-By:` trailer naming the tool.
- Disclosure is expected and neutral: it helps reviewers decide where to look harder. **Not disclosing** is the problem, not using AI.

## 5. When one of our services calls an LLM

Example: operations asks for a feature that summarises dead-lettered payment notifications with an LLM. Minimum controls before it ships:

| Area | Control |
|---|---|
| **Purpose and scope** | Read-only and **advisory**: it summarises and groups (e.g. "4,198 `INVALID_FIELD` errors since 23:41, references not matching `POL-CO-…`"). It never replays, deletes, edits messages or writes to Salesforce. Any action stays with a human through the existing ops tools. |
| **Data minimisation** | Input is canonical events and error metadata only: ids, country, status, error codes, timestamps, reference *resolution*. **No payer personal data** and no raw payloads; free-text fields are excluded or redacted. Same allow-list as our logs. |
| **Approved model endpoint** | Enterprise LLM service in an approved region (e.g. Azure OpenAI in our tenant) with no training on our data and no retention beyond abuse monitoring, accessed with **managed identity**. Legal and privacy review per country (MX, CO, CL data protection laws). |
| **Prompt injection** | DLQ content comes from outside (provider fields, payer-typed references), so it is **untrusted data**: delimited in the prompt, never treated as instructions. The LLM has **no tools or credentials**, so a malicious payload cannot trigger actions. |
| **Output validation** | Structured output (JSON schema) validated by code; every claim cites message ids or counts that the service recomputes deterministically. Counts in the UI come from the database, not from the LLM. Output is labelled "AI-generated summary". |
| **Access and audit** | Same RBAC as the ops API (`payments.ops.read`). Every request is logged with the user, the input ids and the model version, without personal data. |
| **Quality and safety** | A versioned prompt in the repository; an evaluation set built from past incidents (including INC-0412) run in CI when the prompt or model changes; user feedback ("useful / wrong") is tracked. |
| **Reliability and cost** | Timeouts, rate limits and a monthly cost cap. If the LLM is unavailable, the ops API still shows the deterministic DLQ breakdown, so on-call never depends on the LLM. A **kill switch** (feature flag) can disable it immediately. |
