# Contributing to OutcomeProbe

Thank you for helping improve OutcomeProbe. The 0.1.0 release-candidate scope is
deliberately narrow: a single-operator, self-hosted HTTP/JSON resilience
workbench for local, test, and CI environments.

## Before you start

- Search existing issues before opening a new one.
- Use a public issue only for non-sensitive bugs, proposals, and documentation
  questions.
- Never include credentials, customer data, private API descriptions, internal
  hostnames, service logs, or vulnerability details in an issue or pull request.
- Report security vulnerabilities only through the private process in
  [SECURITY.md](SECURITY.md).
- Significant architecture or public contract changes should include an ADR in
  docs/adr that explains context, options, the selected design, and trade-offs.

All participation is governed by [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

## Development environment

- Java 21
- Node.js 24 LTS
- Docker for PostgreSQL integration, Compose, and end-to-end verification

Run the normal source checks:

~~~bash
./gradlew clean build
npm --prefix web ci
npm --prefix web run test
npm --prefix web run build
~~~

Changes to Compose, networking, PostgreSQL behavior, callbacks, probes, or
release packaging also require the relevant Docker-backed checks. Describe the
commands and results in the pull request.

## Code and design rules

- Preserve the modular-monolith and port/adapter boundaries.
- Do not perform remote HTTP calls inside a database transaction.
- Keep Provider truth, HTTP observation, Callback delivery, and Target final
  state as separate facts.
- Make retry, lease, idempotency, transaction, and failure semantics explicit.
- Do not add production branches that exist only to make a test pass.
- Do not add Lombok, arbitrary reflection, or user-provided script execution.
- Domain and application code must use injected clock, ID, and seed providers
  instead of reading global time or randomness directly.
- Never log Authorization headers, cookies, secret values, or sensitive request
  bodies.

The current build enforces Korean Javadoc/TSDoc and Korean comments for important
concurrency and trust-boundary logic. Follow the style of the surrounding module
so that the repository quality gates remain green.

## Contributing integration artifacts

OutcomeProbe Core 0.1.0 accepts three individual, versioned YAML artifact kinds:

- ProviderContract
- TargetProfile
- Scenario

ScenarioPack archives, pack manifests, directory import, and bundled pack
distribution are not supported. Do not add them through a new alias or wrapper.

An artifact contribution should:

- describe a generic, publicly explainable integration failure;
- use synthetic identifiers and data;
- avoid copying a private API or non-public protocol;
- contain no production URL, credential, personal data, or internal hostname;
- use the narrowest practical host, port, path, and method allowlists;
- keep secrets behind secretRef values;
- use a read-only HTTP GET endpoint for final-state observation;
- document the expected vulnerable and resilient outcomes when both are part of
  the example; and
- include tests or a self-contained fixture that proves the intended verdict.

ProviderContract, TargetProfile, and Scenario files must be validated and
imported individually. A contribution may add one or more of these files, but
every referenced artifact must be available to the test that exercises it.

## Pull request checklist

- The change stays within the stated 0.1.0 scope, or an ADR justifies the scope
  change.
- Tests reproduce the defect or protect the new invariant.
- Failure, cancellation, and recovery paths remain distinct.
- Public documentation describes supported behavior without claiming production
  readiness or independent external adoption.
- No secret, personal data, internal path, generated evidence, build output, or
  private service detail is included.
- Relevant unit, integration, Web, and Docker checks are listed with their
  results.

## Commit messages

Use a focused message that describes one behavior:

~~~text
feat(scenario): add an out-of-order callback example
fix(delivery): recover an expired callback lease
test(probe): preserve the parent-run terminal fence
docs(integration): narrow the target allowlist example
~~~

Avoid generic subjects such as wip, temp, update, or misc.
