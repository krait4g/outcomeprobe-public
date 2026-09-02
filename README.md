# OutcomeProbe

[한국어 README](README.ko.md)

**Break the integration on purpose. Prove the business state still converges.**

OutcomeProbe is a self-hosted workbench for testing how an application behaves
when an external HTTP API or webhook fails in an ambiguous way. It separates
what the provider committed, what the caller observed, what callbacks were
delivered, and what final state the target application reached.

> Status: v0.1.0-rc. The supported deployment is a single-operator,
> single-server/PostgreSQL instance for local, test, and CI environments.
> It is not an internet-facing production service.
>
> This public repository is source-only. Build the server and CLI from the
> checked-out source; no prebuilt public artifacts are part of this candidate.

## Why OutcomeProbe

A normal mock usually verifies a happy path:

~~~text
request → 200 OK → webhook → done
~~~

Real integrations fail across independent boundaries:

~~~text
provider commits success → HTTP response is lost
callback v2 arrives → callback v1 arrives late
the same callback arrives three times
no callback arrives → the application remains PROCESSING
~~~

OutcomeProbe models four facts separately:

1. **Provider truth** — what the provider actually committed.
2. **HTTP observation** — what the target application received.
3. **Callback delivery** — whether a webhook was delayed, duplicated, reordered,
   omitted, or signed incorrectly.
4. **Target final state** — what the application eventually believes.

A run passes only when the configured final-state assertions pass. An HTTP 2xx
response by itself is never treated as proof of business success.

## What it provides

- A stateful Provider Emulator that can commit first and then drop, delay, or
  replace the HTTP response.
- Deterministic callback scheduling for duplicate, delay, reorder, omission, and
  signature-failure scenarios.
- Read-only HTTP/JSON state probes with eventual-consistency assertions.
- Versioned YAML artifacts: ProviderContract, TargetProfile, and Scenario.
- Host, port, path, and method allowlists for outbound target requests.
- Secret references backed by environment variables or a read-only secret
  directory; raw secret values do not belong in YAML.
- A Web operator UI and a CI-friendly CLI.
- JSON, standalone HTML, JUnit XML, Timeline evidence, and a strict run bundle.
- PostgreSQL-backed run state, leases, compare-and-set updates, idempotency, and
  append-only evidence boundaries.

ScenarioPack archives and directory import are not supported in the 0.1.0 core.
Artifacts are validated and imported as individual YAML files.

## Docker-only Quick Start

### Prerequisites

- Docker Engine or Docker Desktop
- Docker Compose 2.30 or later
- Git only when using the clone command below

After the source has been obtained, the Quick Start does not require host Java,
Gradle, Node.js, Bash, curl, or jq. GitHub's source download can be used instead
of Git, but commands must be run from the extracted repository root.

### 1. Clone the public source repository

~~~bash
git clone https://github.com/krait4g/outcomeprobe-public.git
cd outcomeprobe-public
~~~

### 2. Create local configuration

Linux or macOS:

~~~bash
cp .env.example .env
~~~

Windows PowerShell:

~~~powershell
Copy-Item .env.example .env
~~~

Edit .env and assign three different, randomly generated values:

- OUTCOMEPROBE_DB_PASSWORD
- OUTCOMEPROBE_DEMO_CALLBACK_SECRET
- OUTCOMEPROBE_CONTROL_API_TOKEN

The operator token must be a 32–512 character token68 value. Do not commit .env
or reuse these values for a real target service.

### 3. Start the stack and import the reference artifacts

~~~bash
docker compose up -d --build --wait --wait-timeout 600
docker compose run --rm -T --no-deps --build outcomeprobe-bootstrap
~~~

The first command starts PostgreSQL, the server, the Web UI, and vulnerable and
resilient reference consumers. The second command validates and imports one
ProviderContract, two TargetProfiles, and two payment Scenarios. It is
idempotent and removes its transient bootstrap container after success.

### 4. Run the reference journey

1. Open http://127.0.0.1:3000.
2. Enter the OUTCOMEPROBE_CONTROL_API_TOKEN value from .env.
3. Run **timeout-after-commit** against the vulnerable consumer and expect
   FAILED. This is an intentional product verdict: the provider committed, but
   the target did not recover.
4. Run the same Scenario against the resilient consumer and expect PASSED.
5. Open the Run detail page to inspect the Timeline and download JSON, JUnit,
   HTML, or the run bundle.

The Overview buttons cover timeout-after-commit. To inspect duplicate callback
handling, open the payment-success-callback-duplicate Scenario and run it once
against each target. The vulnerable target should fail because it applies the
side effect repeatedly; the resilient target should pass because it deduplicates
the event.

### 5. Stop the stack

Preserve PostgreSQL data:

~~~bash
docker compose down --remove-orphans
~~~

Delete all Quick Start data only when you intentionally want a clean database:

~~~bash
docker compose down --volumes --remove-orphans
~~~

The second command permanently removes the PostgreSQL volume. It is not a
normal restart or first-line troubleshooting command.

For detailed troubleshooting, see [QUICKSTART.md](QUICKSTART.md).

## Connect an HTTP/JSON service

OutcomeProbe can connect to a service without modifying the OutcomeProbe Java
code, but the target service must expose a compatible test boundary:

- its external Provider base URL must be configurable to the OutcomeProbe
  Emulator;
- it must expose an HTTP endpoint that triggers the business operation;
- it must expose a read-only HTTP GET endpoint for final-state observation;
- callback scenarios require a webhook endpoint;
- OutcomeProbe and the target must be mutually reachable on the selected test
  network; inside a container, localhost refers to that container;
- target credentials must be supplied through secret references; and
- all runs must use isolated, non-production test data.

The basic connection flow is:

1. Define the provider operations and callbacks in a ProviderContract.
2. Define the target base URI and least-privilege network allowlists in a
   TargetProfile.
3. Define the trigger, injected failure, callback schedule, probe, and business
   assertions in a Scenario.
4. Validate and import each YAML file individually.
5. Verify the TargetProfile, execute the Scenario, and inspect the final verdict
   and evidence.

The repository-owned realtime example demonstrates a single event, a duplicate
event, and out-of-order delivery:

[examples/realtime-track/README.md](examples/realtime-track/README.md)

For the complete contract and network guidance, see
[docs/integration-guide.md](docs/integration-guide.md).

## CLI use in CI

Build the current source CLI:

~~~bash
./gradlew :cli:shadowJar
~~~

Then authenticate through the environment and run individual artifacts:

~~~bash
export OUTCOMEPROBE_CONTROL_API_TOKEN='<same value as the server>'

java -jar cli/build/libs/outcomeprobe-cli-0.1.0.jar doctor
java -jar cli/build/libs/outcomeprobe-cli-0.1.0.jar import provider-contract.yaml
java -jar cli/build/libs/outcomeprobe-cli-0.1.0.jar import target-profile.yaml
java -jar cli/build/libs/outcomeprobe-cli-0.1.0.jar run-file scenario.yaml \
  --target my-app \
  --wait \
  --junit build/test-results/outcomeprobe.xml
~~~

The CLI can also read the operator token from a permission-restricted
--token-file. Do not place tokens in URLs or command-line token arguments.

Exit codes are stable for CI:

| Code | Meaning |
|---:|---|
| 0 | All assertions passed |
| 1 | Scenario assertion failed |
| 2 | Invalid CLI or artifact input |
| 3 | Server, network, or OutcomeProbe error |
| 4 | Run cancelled |

The public v0.1.0-rc repository is source-only. Build the CLI from the checked-out
source; no prebuilt public CLI artifact is claimed.

## Architecture

OutcomeProbe is a modular monolith. Scenario compilation, execution, Provider
emulation, callback delivery, probing, evidence, reports, and target profiles
have explicit module boundaries while sharing one deployable server and one
PostgreSQL database.

This keeps transaction, row-lock, lease, and evidence consistency visible
without introducing a distributed control plane for the first release. The
trade-off is that the current operational baseline is one server process and one
PostgreSQL instance; high availability is not claimed.

## Security and operational boundaries

The default Compose configuration publishes only the Web UI and server on
127.0.0.1. PostgreSQL and both reference consumers have no host-published port.

All control API and Prometheus requests require one static Bearer operator token.
Health and info endpoints are public for local probes, and Emulator ingress has
a separate optional token. This is a minimal single-operator boundary, not a
general authentication platform.

Do not expose the default stack directly to the internet. OutcomeProbe does not
provide:

- TLS termination, OIDC, RBAC, multitenancy, rate limiting, or per-user audit;
- zero-downtime dual-token rotation;
- a production traffic gateway;
- high availability or automatic PostgreSQL failover;
- custom CA or mutual-TLS integration as a supported workflow;
- arbitrary user scripts or shell execution in Scenario YAML; or
- exactly-once callback delivery.

See [SECURITY.md](SECURITY.md) before connecting any service.

## Supported scope

| Supported in v0.1.0-rc | Not claimed |
|---|---|
| Local and controlled CI use | Internet-facing production hosting |
| Single operator, server, and PostgreSQL | HA, failover, RBAC, or multitenancy |
| HTTP/JSON target calls and GET state probes | Kafka-, MQTT-, gRPC-, TCP-, DB-, or SSH-only targets |
| Static Bearer headers and HMAC callback examples | Dynamic OAuth flows, custom CA, or mTLS |
| Included payment reference flow | Processing real payments or bookings |
| Repository-owned HTTP/JSON conformance fixture | Validation by an independent production user |
| At-least-once callback behavior testing | Exactly-once delivery guarantee |

## Documentation

- [Docker-only Quick Start](QUICKSTART.md)
- [External target integration](docs/integration-guide.md)
- [Security policy](SECURITY.md)
- [Contributing](CONTRIBUTING.md)

## Development

~~~bash
./gradlew clean build
npm --prefix web ci
npm --prefix web run test
npm --prefix web run build
~~~

Docker-backed integration and end-to-end checks require Docker. See
[CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.

## License

Apache License 2.0. Third-party notices are provided in
[THIRD-PARTY-NOTICES](THIRD-PARTY-NOTICES) and
[THIRD-PARTY-LICENSES.json](THIRD-PARTY-LICENSES.json).
