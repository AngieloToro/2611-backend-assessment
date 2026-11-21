# CI/CD pipelines (GitHub Actions)

| Workflow | Trigger | Purpose |
|---|---|---|
| [`ci.yml`](ci.yml) | Every pull request, pushes to `main` | Quality and security gates. All jobs are required status checks for merging. |
| [`cd.yml`](cd.yml) | Push to `main`, manual | Build once, deploy dev → test → prod. **Defined, not executed**: skipped unless `AZURE_DEPLOY_ENABLED == 'true'`. |
| [`deploy-environment.yml`](deploy-environment.yml) | Called by `cd.yml` | what-if → deploy infra + apps → migrations → health verification for one environment |

## CI gates and why each one is essential

| Gate | Tool | Why it is essential here |
|---|---|---|
| Build, unit, contract and integration tests | Maven `verify`, JUnit 5 | Proves signature verification against the providers' **test vectors**, idempotency, the 503-before-acceptance rule and the absence of PII in logs. `CanonicalEventContractTest` fails if an event stops matching the published JSON Schema. The enforcer fails fast on a wrong JDK. |
| API contract lint | Redocly CLI | Providers integrate against the OpenAPI document; an invalid contract is a broken integration. |
| IaC build and lint | Bicep CLI (`lint`, `build`, `build-params` for dev/test/prod) | Catches schema errors, insecure parameters and unsupported properties before any deployment. **Warnings fail the build**: during development the linter caught a property not supported by the API version in use. |
| Secret scanning (full history) | gitleaks | PR-214 committed a live webhook secret. A secret in Git cannot be revoked by deleting it, so this has to be blocked before merge. Only documented fictional test secrets are allow-listed (`.gitleaks.toml`). |
| Static analysis | CodeQL (security-and-quality) | Injection, unsafe deserialisation and crypto misuse in an internet-facing service. |
| Dependency review | GitHub dependency review | Blocks new dependencies with high/critical vulnerabilities or incompatible licences. |
| Container scan | Trivy | The runtime image (distroless, non-root) must not ship fixable HIGH/CRITICAL vulnerabilities. |

Not included on purpose: code-coverage thresholds (they reward quantity over behaviour; the review checks that tests assert behaviour, see PR-214 finding #10) and full DAST (no deployed environment in CI; it belongs to the test environment).

## CD controls

| Control | How |
|---|---|
| **Authentication without stored secrets** | `azure/login` with **OIDC**. Each GitHub environment (`build`, `dev`, `test`, `prod-plan`, `prod`) maps to its own Entra ID app registration with a federated credential for `repo:<owner>/<repo>:environment:<env>`. Only non-secret ids (`AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`) are stored, as environment **variables**. |
| Least privilege for the pipeline | The deploy identity of each environment has rights only on that environment's resource group (Contributor + RBAC Administrator restricted by condition to the data-plane roles the template assigns). |
| Build once, promote | The image tag is the commit SHA; dev, test and prod run the exact same image. An SBOM is generated at build time. |
| Infrastructure changes | Same Bicep deployment as the application, `Incremental` mode. **what-if** runs first in every environment and is published in the job summary; the job fails if what-if contains deletions. |
| Ordered promotion | dev → test → prod via `needs`; a failure stops the promotion. |
| Production approval | `plan-prod` publishes the prod what-if without approval. `deploy-prod` runs in the `prod` environment, which requires **manual approval by a reviewer other than the author**, accepts deployments from `main` only, and has a wait timer. Recommended freeze: no prod deployments during the month-end peak (last two and first two days of the month) unless it is a hotfix. |
| Verification and rollback | After deploying, the pipeline waits for every Container App revision to be `Healthy`. Rollback = re-run CD for the previous commit (same image tag still in the registry); single-revision mode makes the switch immediate. Database migrations are backward compatible (expand/contract) so an application rollback never needs a schema rollback. |
| Secrets for deployment | The only secret parameter (on-call webhook URL) is a GitHub **environment secret** in `prod`, passed through an environment variable and declared `@secure()` in Bicep, so it never appears in logs or deployment history. |

## Setup needed to enable CD (not done in this repository)

1. Create GitHub environments `build`, `dev`, `test`, `prod-plan`, `prod` with the protection rules above.
2. Create one Entra ID app per environment with the federated credential and the role assignments on its resource group.
3. Set the variables `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`, `AZURE_RESOURCE_GROUP`, `ACR_NAME` per environment, `ONCALL_WEBHOOK_URL` as a secret in `prod`, and the repository variable `AZURE_DEPLOY_ENABLED=true`.

## Supply-chain hardening still to do

Third-party actions are referenced by major version tag for readability. In the real repository they would be **pinned to commit SHAs** and kept current by Dependabot, and images would be signed (cosign) and verified at deploy time. Database migrations would move from processor start-up to a dedicated Container Apps job run by the pipeline before the new revisions.
