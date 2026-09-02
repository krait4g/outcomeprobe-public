# Security Policy

## Supported scope

OutcomeProbe v0.1.0-rc is a source-only pre-release for local, test, and
controlled CI environments. It is not a production traffic gateway and must not
be exposed directly to the internet.

Only the latest public v0.1.0-rc source revision is considered for security
fixes. Build from the checked-out source; no prebuilt public artifacts are part
of this candidate.

## Report a vulnerability privately

Do not report a vulnerability, exploit, credential, or sensitive log in a public
GitHub Issue, Discussion, pull request, or commit.

This repository uses **GitHub Private Vulnerability Reporting** as its security
reporting channel:

1. Open the repository **Security** tab.
2. Select **Report a vulnerability**.
3. Submit the report through the private advisory form.

Public launch assumes that repository maintainers have enabled GitHub Private
Vulnerability Reporting. If the **Report a vulnerability** button is not
available, do not post the details publicly. The missing private channel should
be treated as a repository configuration defect and corrected before public
distribution. No private security email address is claimed in this document.

Include only the information needed to reproduce and assess the issue:

- affected source revision and configuration;
- prerequisites and impact;
- minimal reproduction steps;
- a sanitized Scenario YAML or request example, when relevant; and
- a proposed mitigation, if available.

Do not attach a live secret or production dataset. Replace sensitive values with
synthetic markers. If a real credential was exposed, revoke or rotate it before
submitting the report.

## Supported security boundary

The default Compose configuration is intentionally local:

- the Web UI and server bind to 127.0.0.1;
- PostgreSQL and the reference consumers do not publish host ports;
- all control API and Prometheus requests require
  OUTCOMEPROBE_CONTROL_API_TOKEN as a Bearer token;
- health and info endpoints remain public for local readiness probes; and
- Emulator ingress has a separate optional token.

The operator token is a single-operator control boundary. It is not a substitute
for TLS, an identity provider, or network isolation.

## Secret handling

- Do not put raw secret values in ProviderContract, TargetProfile, Scenario,
  URLs, command-line token arguments, logs, or committed .env files.
- Supply Target secrets through secretRef values backed by environment variables
  or a read-only secret directory.
- Use a permission-restricted --token-file for the CLI when an environment
  variable is not appropriate.
- The Web UI stores the operator token in tab-scoped sessionStorage. This is not
  an XSS-resistant secret vault.
- Authorization, Cookie, and configured secret headers are redacted from
  Evidence, but users must still avoid production payloads and personal data.
- Use different values for the database password, demo callback secret, control
  token, Emulator token, and Target credentials.

## Target and network safety

- Scenario documents cannot contain arbitrary absolute target URLs. Outbound
  requests are resolved through a registered TargetProfile.
- TargetProfiles must use least-privilege host, port, path, and method
  allowlists.
- State observation must use a read-only HTTP GET endpoint.
- Redirect and private-network decisions are defense-in-depth controls, not a
  replacement for an isolated test network and egress policy.
- Connect only services and data that are explicitly authorized for resilience
  testing.

## Not provided

OutcomeProbe v0.1.0-rc does not provide:

- internet-facing production hardening;
- TLS termination, OIDC, RBAC, multitenancy, rate limiting, or per-user audit;
- zero-downtime dual-token rotation;
- high availability or automatic PostgreSQL failover;
- a supported custom-CA or mutual-TLS workflow;
- arbitrary JavaScript, shell, SpEL, or other user script execution in Scenario
  artifacts;
- exactly-once callback delivery; or
- PCI DSS compliance or complete security validation of an external system.

Callback delivery is intentionally at least once. OutcomeProbe verifies whether
the Target handles duplicates and ambiguity safely; it does not remove those
conditions.
