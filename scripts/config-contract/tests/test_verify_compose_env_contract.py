#!/usr/bin/env python3
"""Regression tests for the public Compose environment contract verifier."""

from __future__ import annotations

import contextlib
import copy
import importlib.util
import io
import json
import pathlib
import subprocess
import tempfile
import unittest


SCRIPT = pathlib.Path(__file__).parents[1] / "verify_compose_env_contract.py"
SPEC = importlib.util.spec_from_file_location("verify_compose_env_contract", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)

SECRET_SENTINEL = "never-print-this-secret-7e6157"


def valid_env_values() -> dict[str, str]:
    return {
        "OUTCOMEPROBE_SERVER_PORT": "18080",
        "OUTCOMEPROBE_WEB_PORT": "13000",
        "OUTCOMEPROBE_DB_NAME": "contract_db",
        "OUTCOMEPROBE_DB_USER": "contract_user",
        "OUTCOMEPROBE_DB_PASSWORD": "",
        "OUTCOMEPROBE_DEMO_CALLBACK_SECRET": "",
        "OUTCOMEPROBE_CONTROL_API_TOKEN": "",
        "OUTCOMEPROBE_RETENTION_DAYS": "23",
        "OUTCOMEPROBE_MAX_CONCURRENT_RUNS": "7",
        "OUTCOMEPROBE_DEMO_PROFILE_SEEDING_ENABLED": "false",
        "OUTCOMEPROBE_EMULATOR_TOKEN": "",
        "OUTCOMEPROBE_LOG_LEVEL": "WARN",
    }


def dotenv(values: dict[str, str]) -> str:
    return "\n".join(f"{key}={value}" for key, value in values.items()) + "\n"


VALID_COMPOSE = """\
services:
  postgres:
    image: postgres:18.4
    environment:
      POSTGRES_DB: ${OUTCOMEPROBE_DB_NAME:-outcomeprobe}
      POSTGRES_USER: ${OUTCOMEPROBE_DB_USER:-outcomeprobe}
      POSTGRES_PASSWORD: ${OUTCOMEPROBE_DB_PASSWORD:?database password required}
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${OUTCOMEPROBE_DB_USER:-outcomeprobe} -d ${OUTCOMEPROBE_DB_NAME:-outcomeprobe}"]
    networks:
      - data
  outcomeprobe-server:
    environment:
      OUTCOMEPROBE_DB_HOST: postgres
      OUTCOMEPROBE_DB_PORT: "5432"
      OUTCOMEPROBE_DB_NAME: ${OUTCOMEPROBE_DB_NAME:-outcomeprobe}
      OUTCOMEPROBE_DB_USER: ${OUTCOMEPROBE_DB_USER:-outcomeprobe}
      OUTCOMEPROBE_DB_PASSWORD: ${OUTCOMEPROBE_DB_PASSWORD:?database password required}
      OUTCOMEPROBE_DEMO_CALLBACK_SECRET: ${OUTCOMEPROBE_DEMO_CALLBACK_SECRET:?callback secret required}
      OUTCOMEPROBE_CONTROL_API_TOKEN: ${OUTCOMEPROBE_CONTROL_API_TOKEN:?control token required}
      OUTCOMEPROBE_RETENTION_DAYS: ${OUTCOMEPROBE_RETENTION_DAYS:-14}
      OUTCOMEPROBE_MAX_CONCURRENT_RUNS: ${OUTCOMEPROBE_MAX_CONCURRENT_RUNS:-10}
      OUTCOMEPROBE_DEMO_PROFILE_SEEDING_ENABLED: ${OUTCOMEPROBE_DEMO_PROFILE_SEEDING_ENABLED:-false}
      OUTCOMEPROBE_EMULATOR_TOKEN: ${OUTCOMEPROBE_EMULATOR_TOKEN:-}
      OUTCOMEPROBE_LOG_LEVEL: ${OUTCOMEPROBE_LOG_LEVEL:-INFO}
    ports:
      - "127.0.0.1:${OUTCOMEPROBE_SERVER_PORT:-8080}:8080"
    networks:
      - app
      - data
  outcomeprobe-web:
    ports:
      - "127.0.0.1:${OUTCOMEPROBE_WEB_PORT:-3000}:3000"
    depends_on:
      outcomeprobe-server:
        condition: service_healthy
    networks:
      - app
  reference-consumer-vulnerable:
    environment:
      OUTCOMEPROBE_EMULATOR_BASE_URL: http://outcomeprobe-server:8080/emulator/mockpay
      OUTCOMEPROBE_BOOKING_EMULATOR_BASE_URL: http://outcomeprobe-server:8080/emulator/mockbooking
      OUTCOMEPROBE_DEMO_CALLBACK_SECRET: ${OUTCOMEPROBE_DEMO_CALLBACK_SECRET:?callback secret required}
      OUTCOMEPROBE_EMULATOR_TOKEN: ${OUTCOMEPROBE_EMULATOR_TOKEN:-}
    networks:
      app:
        aliases:
          - rr-vulnerable
  reference-consumer-resilient:
    environment:
      OUTCOMEPROBE_EMULATOR_BASE_URL: http://outcomeprobe-server:8080/emulator/mockpay
      OUTCOMEPROBE_BOOKING_EMULATOR_BASE_URL: http://outcomeprobe-server:8080/emulator/mockbooking
      OUTCOMEPROBE_DEMO_CALLBACK_SECRET: ${OUTCOMEPROBE_DEMO_CALLBACK_SECRET:?callback secret required}
      OUTCOMEPROBE_EMULATOR_TOKEN: ${OUTCOMEPROBE_EMULATOR_TOKEN:-}
    networks:
      app:
        aliases:
          - rr-resilient
  outcomeprobe-bootstrap:
    build:
      context: .
      dockerfile: docker/bootstrap.Dockerfile
    environment:
      OUTCOMEPROBE_SERVER_URL: http://outcomeprobe-server:8080
      OUTCOMEPROBE_CONTROL_API_TOKEN: ${OUTCOMEPROBE_CONTROL_API_TOKEN:?OUTCOMEPROBE_CONTROL_API_TOKEN must be set}
    profiles:
      - tools
    read_only: true
    tmpfs:
      - /tmp:rw,noexec,nosuid,nodev,size=16m,mode=1777
    cap_drop:
      - ALL
    security_opt:
      - no-new-privileges:true
    pids_limit: 128
    restart: "no"
    depends_on:
      outcomeprobe-server:
        condition: service_healthy
      reference-consumer-vulnerable:
        condition: service_healthy
      reference-consumer-resilient:
        condition: service_healthy
    networks:
      - app
networks:
  app:
  data:
    internal: true
volumes:
  outcomeprobe-postgres-data:
"""


VALID_OVERRIDE = """\
services:
  outcomeprobe-server:
    volumes:
      - type: bind
        source: ${OUTCOMEPROBE_SECRETS_HOST_DIRECTORY:?secret host directory required}
        target: /run/outcomeprobe-secrets
        read_only: true
    environment:
      OUTCOMEPROBE_SECRETS_DIRECTORY: /run/outcomeprobe-secrets
"""


VALID_SERVER_APPLICATION = """\
spring:
  datasource:
    password: ${OUTCOMEPROBE_DB_PASSWORD}
outcomeprobe:
  security:
    control-api-token: ${OUTCOMEPROBE_CONTROL_API_TOKEN}
  retention:
    days: ${OUTCOMEPROBE_RETENTION_DAYS:14}
  limits:
    max-concurrent-runs: ${OUTCOMEPROBE_MAX_CONCURRENT_RUNS:10}
  emulator:
    token: ${OUTCOMEPROBE_EMULATOR_TOKEN:}
  demo:
    profile-seeding-enabled: ${OUTCOMEPROBE_DEMO_PROFILE_SEEDING_ENABLED:false}
logging:
  level:
    io.outcomeprobe: ${OUTCOMEPROBE_LOG_LEVEL:INFO}
"""


VALID_REFERENCE_APPLICATION = """\
reference-consumer:
  provider:
    base-url: ${OUTCOMEPROBE_EMULATOR_BASE_URL:http://localhost:8080/emulator/mockpay}
  booking-provider:
    base-url: ${OUTCOMEPROBE_BOOKING_EMULATOR_BASE_URL:http://localhost:8080/emulator/mockbooking}
  emulator-token: ${OUTCOMEPROBE_EMULATOR_TOKEN:}
  webhook:
    signing-secret: ${OUTCOMEPROBE_DEMO_CALLBACK_SECRET}
"""


def port(host: str, published: str, target: int) -> dict[str, object]:
    return {
        "host_ip": host,
        "published": published,
        "target": target,
        "protocol": "tcp",
    }


def rendered_config(
    values: dict[str, str],
    secret_source: pathlib.Path | None = None,
    *,
    include_bootstrap: bool = True,
) -> dict[str, object]:
    server_environment = {
        "OUTCOMEPROBE_DB_HOST": "postgres",
        "OUTCOMEPROBE_DB_PORT": "5432",
        "OUTCOMEPROBE_DB_NAME": values["OUTCOMEPROBE_DB_NAME"],
        "OUTCOMEPROBE_DB_USER": values["OUTCOMEPROBE_DB_USER"],
        "OUTCOMEPROBE_DB_PASSWORD": values["OUTCOMEPROBE_DB_PASSWORD"],
        "OUTCOMEPROBE_DEMO_CALLBACK_SECRET": values[
            "OUTCOMEPROBE_DEMO_CALLBACK_SECRET"
        ],
        "OUTCOMEPROBE_CONTROL_API_TOKEN": values["OUTCOMEPROBE_CONTROL_API_TOKEN"],
        "OUTCOMEPROBE_RETENTION_DAYS": values["OUTCOMEPROBE_RETENTION_DAYS"],
        "OUTCOMEPROBE_MAX_CONCURRENT_RUNS": values[
            "OUTCOMEPROBE_MAX_CONCURRENT_RUNS"
        ],
        "OUTCOMEPROBE_DEMO_PROFILE_SEEDING_ENABLED": values[
            "OUTCOMEPROBE_DEMO_PROFILE_SEEDING_ENABLED"
        ],
        "OUTCOMEPROBE_EMULATOR_TOKEN": values["OUTCOMEPROBE_EMULATOR_TOKEN"],
        "OUTCOMEPROBE_LOG_LEVEL": values["OUTCOMEPROBE_LOG_LEVEL"],
    }
    server: dict[str, object] = {
        "environment": server_environment,
        "ports": [port("127.0.0.1", values["OUTCOMEPROBE_SERVER_PORT"], 8080)],
        "networks": {"app": None, "data": None},
    }
    if secret_source is not None:
        server_environment["OUTCOMEPROBE_SECRETS_DIRECTORY"] = (
            "/run/outcomeprobe-secrets"
        )
        server["volumes"] = [
            {
                "type": "bind",
                "source": str(secret_source),
                "target": "/run/outcomeprobe-secrets",
                "read_only": True,
            }
        ]
    bootstrap: dict[str, object] = {
        "build": {
            "context": str(pathlib.Path.cwd()),
            "dockerfile": "docker/bootstrap.Dockerfile",
        },
        "environment": {
            "OUTCOMEPROBE_SERVER_URL": "http://outcomeprobe-server:8080",
            "OUTCOMEPROBE_CONTROL_API_TOKEN": values[
                "OUTCOMEPROBE_CONTROL_API_TOKEN"
            ],
        },
        "profiles": ["tools"],
        "ports": [],
        "networks": {"app": None},
        "depends_on": {
            "outcomeprobe-server": {
                "condition": "service_healthy",
                "required": True,
            },
            "reference-consumer-vulnerable": {
                "condition": "service_healthy",
                "required": True,
            },
            "reference-consumer-resilient": {
                "condition": "service_healthy",
                "required": True,
            },
        },
        "read_only": True,
        "tmpfs": [
            "/tmp:rw,noexec,nosuid,nodev,size=16m,mode=1777",
        ],
        "cap_drop": ["ALL"],
        "security_opt": ["no-new-privileges:true"],
        "pids_limit": 128,
        "restart": "no",
    }
    return {
        "services": {
            "postgres": {
                "environment": {
                    "POSTGRES_DB": values["OUTCOMEPROBE_DB_NAME"],
                    "POSTGRES_USER": values["OUTCOMEPROBE_DB_USER"],
                    "POSTGRES_PASSWORD": values["OUTCOMEPROBE_DB_PASSWORD"],
                },
                "networks": {"data": None},
            },
            "outcomeprobe-server": server,
            "outcomeprobe-web": {
                "ports": [
                    port("127.0.0.1", values["OUTCOMEPROBE_WEB_PORT"], 3000)
                ],
                "networks": {"app": None},
                "depends_on": {
                    "outcomeprobe-server": {
                        "condition": "service_healthy",
                        "required": True,
                    }
                },
            },
            "reference-consumer-vulnerable": {
                "environment": {
                    "OUTCOMEPROBE_EMULATOR_BASE_URL": (
                        "http://outcomeprobe-server:8080/emulator/mockpay"
                    ),
                    "OUTCOMEPROBE_BOOKING_EMULATOR_BASE_URL": (
                        "http://outcomeprobe-server:8080/emulator/mockbooking"
                    ),
                    "OUTCOMEPROBE_DEMO_CALLBACK_SECRET": values[
                        "OUTCOMEPROBE_DEMO_CALLBACK_SECRET"
                    ],
                    "OUTCOMEPROBE_EMULATOR_TOKEN": values[
                        "OUTCOMEPROBE_EMULATOR_TOKEN"
                    ],
                },
                "networks": {"app": {"aliases": ["rr-vulnerable"]}},
            },
            "reference-consumer-resilient": {
                "environment": {
                    "OUTCOMEPROBE_EMULATOR_BASE_URL": (
                        "http://outcomeprobe-server:8080/emulator/mockpay"
                    ),
                    "OUTCOMEPROBE_BOOKING_EMULATOR_BASE_URL": (
                        "http://outcomeprobe-server:8080/emulator/mockbooking"
                    ),
                    "OUTCOMEPROBE_DEMO_CALLBACK_SECRET": values[
                        "OUTCOMEPROBE_DEMO_CALLBACK_SECRET"
                    ],
                    "OUTCOMEPROBE_EMULATOR_TOKEN": values[
                        "OUTCOMEPROBE_EMULATOR_TOKEN"
                    ],
                },
                "networks": {"app": {"aliases": ["rr-resilient"]}},
            },
            **({"outcomeprobe-bootstrap": bootstrap} if include_bootstrap else {}),
        },
        "networks": {"app": {}, "data": {"internal": True}},
    }


class ComposeEnvironmentContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temporary.name)
        self.env = self.root / ".env.example"
        self.compose = self.root / "compose.yaml"
        self.override = self.root / "compose.secrets-directory.yaml"
        self.server_application = self.root / "server-application.yaml"
        self.reference_application = self.root / "reference-application.yaml"
        self.bootstrap_dockerfile = self.root / "bootstrap.Dockerfile"
        self.env.write_text(dotenv(valid_env_values()), encoding="utf-8")
        self.compose.write_text(VALID_COMPOSE, encoding="utf-8")
        self.override.write_text(VALID_OVERRIDE, encoding="utf-8")
        self.server_application.write_text(
            VALID_SERVER_APPLICATION, encoding="utf-8"
        )
        self.reference_application.write_text(
            VALID_REFERENCE_APPLICATION, encoding="utf-8"
        )
        self.bootstrap_dockerfile.write_text(
            "FROM scratch\nUSER 10001:10001\n", encoding="utf-8"
        )

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def verify_static(self) -> dict[str, str]:
        return VERIFIER.verify_static_contract(
            self.env,
            self.compose,
            self.override,
            bootstrap_dockerfile=self.bootstrap_dockerfile,
        )

    def test_static_contract_accepts_exact_supported_keys_and_optional_empty(self) -> None:
        self.assertEqual(self.verify_static(), valid_env_values())

    def test_quick_start_example_requires_profile_seeding_false(self) -> None:
        values = valid_env_values()
        values["OUTCOMEPROBE_DEMO_PROFILE_SEEDING_ENABLED"] = "true"
        self.env.write_text(dotenv(values), encoding="utf-8")

        with self.assertRaisesRegex(VERIFIER.ContractError, "profile seeding"):
            self.verify_static()

    def test_bootstrap_image_user_is_exact(self) -> None:
        self.bootstrap_dockerfile.write_text(
            "FROM scratch\nUSER root\n", encoding="utf-8"
        )
        with self.assertRaisesRegex(VERIFIER.ContractError, "image user"):
            self.verify_static()

    def test_active_key_set_is_exact_and_error_does_not_leak_values(self) -> None:
        values = valid_env_values()
        values.pop("OUTCOMEPROBE_LOG_LEVEL")
        values["UNSUPPORTED_KEY"] = SECRET_SENTINEL
        self.env.write_text(dotenv(values), encoding="utf-8")

        with self.assertRaises(VERIFIER.ContractError) as raised:
            self.verify_static()

        message = str(raised.exception)
        self.assertIn("OUTCOMEPROBE_LOG_LEVEL", message)
        self.assertNotIn(SECRET_SENTINEL, message)

    def test_public_example_rejects_populated_required_secret_without_leak(self) -> None:
        values = valid_env_values()
        values["OUTCOMEPROBE_DB_PASSWORD"] = SECRET_SENTINEL
        self.env.write_text(dotenv(values), encoding="utf-8")

        with self.assertRaises(VERIFIER.ContractError) as raised:
            self.verify_static()

        message = str(raised.exception)
        self.assertIn("OUTCOMEPROBE_DB_PASSWORD", message)
        self.assertNotIn(SECRET_SENTINEL, message)

    def test_application_property_chain_is_exact_and_dead_properties_fail(self) -> None:
        VERIFIER.verify_application_property_contract(
            self.server_application, self.reference_application
        )

        self.reference_application.write_text(
            VALID_REFERENCE_APPLICATION + "  demo-mode: true\n", encoding="utf-8"
        )
        with self.assertRaisesRegex(VERIFIER.ContractError, "unused"):
            VERIFIER.verify_application_property_contract(
                self.server_application, self.reference_application
            )

    def test_required_interpolation_rejects_default_without_leaking_default(self) -> None:
        self.compose.write_text(
            VALID_COMPOSE.replace(
                "${OUTCOMEPROBE_DB_PASSWORD:?database password required}",
                "${OUTCOMEPROBE_DB_PASSWORD:-" + SECRET_SENTINEL + "}",
            ),
            encoding="utf-8",
        )

        with self.assertRaises(VERIFIER.ContractError) as raised:
            self.verify_static()

        self.assertIn("OUTCOMEPROBE_DB_PASSWORD", str(raised.exception))
        self.assertNotIn(SECRET_SENTINEL, str(raised.exception))

    def test_secret_directory_override_must_be_read_only(self) -> None:
        self.override.write_text(
            VALID_OVERRIDE.replace("read_only: true", "read_only: false"),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(VERIFIER.ContractError, "not read-only"):
            self.verify_static()

    def test_consumer_provider_urls_and_token_mapping_are_required(self) -> None:
        for endpoint in ("mockpay", "mockbooking"):
            docker_url = f"http://outcomeprobe-server:8080/emulator/{endpoint}"
            localhost_url = f"http://localhost:8080/emulator/{endpoint}"
            first = VALID_COMPOSE.index(docker_url)
            second = VALID_COMPOSE.index(docker_url, first + len(docker_url))
            for service, position in (
                ("reference-consumer-vulnerable", first),
                ("reference-consumer-resilient", second),
            ):
                with self.subTest(endpoint=endpoint, service=service):
                    mutated = (
                        VALID_COMPOSE[:position]
                        + localhost_url
                        + VALID_COMPOSE[position + len(docker_url) :]
                    )
                    self.compose.write_text(mutated, encoding="utf-8")
                    with self.assertRaisesRegex(
                        VERIFIER.ContractError, "fixed emulator URL"
                    ):
                        self.verify_static()

        token_line = (
            "      OUTCOMEPROBE_EMULATOR_TOKEN: "
            "${OUTCOMEPROBE_EMULATOR_TOKEN:-}\n"
        )
        first = VALID_COMPOSE.index(token_line)
        consumer_token = VALID_COMPOSE.index(token_line, first + len(token_line))
        without_consumer_token = (
            VALID_COMPOSE[:consumer_token]
            + VALID_COMPOSE[consumer_token + len(token_line) :]
        )
        self.compose.write_text(without_consumer_token, encoding="utf-8")
        with self.assertRaisesRegex(VERIFIER.ContractError, "token mapping"):
            self.verify_static()

    def test_source_consumers_must_not_publish_host_ports(self) -> None:
        for service, alias, port in (
            ("reference-consumer-vulnerable", "rr-vulnerable", 8091),
            ("reference-consumer-resilient", "rr-resilient", 8092),
        ):
            with self.subTest(service=service):
                network_block = (
                    "    networks:\n"
                    "      app:\n"
                    "        aliases:\n"
                    f"          - {alias}"
                )
                published = (
                    "    ports:\n"
                    f'      - "127.0.0.1:1{port}:{port}"\n'
                    + network_block
                )
                self.compose.write_text(
                    VALID_COMPOSE.replace(network_block, published, 1),
                    encoding="utf-8",
                )
                with self.assertRaisesRegex(
                    VERIFIER.ContractError, "must not publish a host port"
                ):
                    self.verify_static()

    def test_source_server_and_web_publish_exact_loopback_ports(self) -> None:
        for service, mapping in (
            (
                "outcomeprobe-server",
                '      - "127.0.0.1:${OUTCOMEPROBE_SERVER_PORT:-8080}:8080"',
            ),
            (
                "outcomeprobe-web",
                '      - "127.0.0.1:${OUTCOMEPROBE_WEB_PORT:-3000}:3000"',
            ),
        ):
            for mutation in (
                mapping.replace("127.0.0.1", "0.0.0.0"),
                mapping + '\n      - "127.0.0.1:19999:9999"',
            ):
                with self.subTest(service=service, mutation=mutation):
                    self.compose.write_text(
                        VALID_COMPOSE.replace(mapping, mutation, 1),
                        encoding="utf-8",
                    )
                    with self.assertRaisesRegex(
                        VERIFIER.ContractError, "exactly one loopback port"
                    ):
                        self.verify_static()

    def test_removed_consumer_port_environment_keys_are_rejected(self) -> None:
        for key, value in (
            ("OUTCOMEPROBE_VULNERABLE_PORT", "18091"),
            ("OUTCOMEPROBE_RESILIENT_PORT", "18092"),
        ):
            with self.subTest(key=key):
                values = valid_env_values()
                values[key] = value
                self.env.write_text(dotenv(values), encoding="utf-8")

                with self.assertRaisesRegex(
                    VERIFIER.ContractError, "unsupported active environment"
                ):
                    self.verify_static()

    def test_source_network_topology_rejects_database_on_app_network(self) -> None:
        self.compose.write_text(
            VALID_COMPOSE.replace(
                "    networks:\n      - data\n  outcomeprobe-server:",
                "    networks:\n      - app\n      - data\n  outcomeprobe-server:",
            ),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(VERIFIER.ContractError, "postgres"):
            self.verify_static()

    def test_static_bootstrap_boundary_mutations_are_rejected(self) -> None:
        mutations = (
            (
                "service inventory",
                "\nnetworks:\n  app:\n",
                "\n  unexpected-service:\n    networks:\n      - app\nnetworks:\n  app:\n",
            ),
            (
                "Dockerfile",
                "dockerfile: docker/bootstrap.Dockerfile",
                "dockerfile: docker/server.Dockerfile",
            ),
            (
                "server URL",
                "OUTCOMEPROBE_SERVER_URL: http://outcomeprobe-server:8080",
                "OUTCOMEPROBE_SERVER_URL: http://localhost:8080",
            ),
            (
                "token environment mapping",
                (
                    "      OUTCOMEPROBE_SERVER_URL: http://outcomeprobe-server:8080\n"
                    "      OUTCOMEPROBE_CONTROL_API_TOKEN: "
                    "${OUTCOMEPROBE_CONTROL_API_TOKEN:"
                    "?OUTCOMEPROBE_CONTROL_API_TOKEN must be set}\n"
                ),
                (
                    "      OUTCOMEPROBE_SERVER_URL: http://outcomeprobe-server:8080\n"
                    "      OUTCOMEPROBE_CONTROL_API_TOKEN: forbidden\n"
                ),
            ),
            (
                "published port",
                "    profiles:\n      - tools\n",
                (
                    "    ports:\n      - \"127.0.0.1:18000:18000\"\n"
                    "    profiles:\n      - tools\n"
                ),
            ),
            (
                "profile seeding default",
                "${OUTCOMEPROBE_DEMO_PROFILE_SEEDING_ENABLED:-false}",
                "${OUTCOMEPROBE_DEMO_PROFILE_SEEDING_ENABLED:-true}",
            ),
            ("vulnerable alias", "rr-vulnerable", "wrong-vulnerable"),
            ("resilient alias", "rr-resilient", "wrong-resilient"),
            (
                "web server health gate",
                "condition: service_healthy",
                "condition: service_started",
            ),
            (
                "bootstrap health gate",
                (
                    '    restart: "no"\n    depends_on:\n'
                    "      outcomeprobe-server:\n"
                    "        condition: service_healthy\n"
                ),
                (
                    '    restart: "no"\n    depends_on:\n'
                    "      outcomeprobe-server:\n"
                    "        condition: service_started\n"
                ),
            ),
            (
                "bootstrap dependency set",
                "    networks:\n      - app\nnetworks:\n",
                (
                    "      unexpected-service:\n"
                    "        condition: service_healthy\n"
                    "    networks:\n      - app\nnetworks:\n"
                ),
            ),
            (
                "bootstrap app-only network",
                "    networks:\n      - app\nnetworks:\n",
                "    networks:\n      - data\nnetworks:\n",
            ),
            (
                "tools profile",
                "    profiles:\n      - tools\n",
                "    profiles:\n      - default\n",
            ),
            (
                "forbidden secret grant",
                "    profiles:\n      - tools\n",
                (
                    "    profiles:\n      - tools\n"
                    "    secrets:\n      - outcomeprobe-control-api-token\n"
                ),
            ),
            (
                "forbidden top-level secret",
                "volumes:\n  outcomeprobe-postgres-data:\n",
                (
                    "volumes:\n  outcomeprobe-postgres-data:\n"
                    "secrets:\n  outcomeprobe-control-api-token:\n"
                    "    environment: OUTCOMEPROBE_CONTROL_API_TOKEN\n"
                ),
            ),
            (
                "forbidden user override",
                "    read_only: true\n",
                '    user: "0:0"\n    read_only: true\n',
            ),
            ("read-only root", "read_only: true", "read_only: false"),
            (
                "forbidden secret tmpfs",
                "      - /tmp:rw,noexec,nosuid,nodev,size=16m,mode=1777\n",
                (
                    "      - /tmp:rw,noexec,nosuid,nodev,size=16m,mode=1777\n"
                    "      - /run/secrets:rw,noexec,nosuid,nodev,size=64k\n"
                ),
            ),
            ("capability drop", "      - ALL\n", "      - NET_RAW\n"),
            (
                "no-new-privileges",
                "no-new-privileges:true",
                "no-new-privileges:false",
            ),
            ("PID limit", "pids_limit: 128", "pids_limit: 1024"),
            ("restart policy", 'restart: "no"', "restart: on-failure"),
        )

        for label, old, new in mutations:
            with self.subTest(label=label):
                self.assertIn(old, VALID_COMPOSE)
                mutated = VALID_COMPOSE.replace(old, new, 1)
                self.assertNotEqual(mutated, VALID_COMPOSE)
                self.compose.write_text(mutated, encoding="utf-8")
                with self.assertRaises(VERIFIER.ContractError):
                    self.verify_static()
        self.compose.write_text(VALID_COMPOSE, encoding="utf-8")

    def test_rendered_contract_accepts_loopback_runtime_and_override(self) -> None:
        source = self.root / "secret-source"
        source.mkdir()
        VERIFIER.verify_rendered_config(
            rendered_config(valid_env_values(), source),
            valid_env_values(),
            secret_source=source,
        )
        VERIFIER.verify_rendered_config(
            rendered_config(
                valid_env_values(), source, include_bootstrap=False
            ),
            valid_env_values(),
            secret_source=source,
            include_bootstrap=False,
        )

    def test_rendered_bootstrap_boundary_mutations_are_rejected(self) -> None:
        mutations: tuple[tuple[str, tuple[object, ...], object], ...] = (
            ("service inventory", ("services", "unexpected-service"), {}),
            (
                "Dockerfile",
                ("services", "outcomeprobe-bootstrap", "build", "dockerfile"),
                "docker/server.Dockerfile",
            ),
            (
                "published port",
                ("services", "outcomeprobe-bootstrap", "ports"),
                [port("127.0.0.1", "18000", 18000)],
            ),
            (
                "environment inventory",
                (
                    "services",
                    "outcomeprobe-bootstrap",
                    "environment",
                    "OUTCOMEPROBE_CONTROL_API_TOKEN",
                ),
                SECRET_SENTINEL,
            ),
            (
                "tools profile",
                ("services", "outcomeprobe-bootstrap", "profiles"),
                ["default"],
            ),
            (
                "profile seeding",
                (
                    "services",
                    "outcomeprobe-server",
                    "environment",
                    "OUTCOMEPROBE_DEMO_PROFILE_SEEDING_ENABLED",
                ),
                "true",
            ),
            (
                "bootstrap network options",
                ("services", "outcomeprobe-bootstrap", "networks", "app"),
                {"aliases": ["bootstrap-alias"]},
            ),
            (
                "consumer alias",
                (
                    "services",
                    "reference-consumer-vulnerable",
                    "networks",
                    "app",
                    "aliases",
                ),
                ["wrong-vulnerable"],
            ),
            (
                "bootstrap dependency condition",
                (
                    "services",
                    "outcomeprobe-bootstrap",
                    "depends_on",
                    "outcomeprobe-server",
                    "condition",
                ),
                "service_started",
            ),
            (
                "bootstrap dependency required",
                (
                    "services",
                    "outcomeprobe-bootstrap",
                    "depends_on",
                    "outcomeprobe-server",
                    "required",
                ),
                False,
            ),
            (
                "web server health gate",
                (
                    "services",
                    "outcomeprobe-web",
                    "depends_on",
                    "outcomeprobe-server",
                    "condition",
                ),
                "service_started",
            ),
            (
                "forbidden secret grant",
                ("services", "outcomeprobe-bootstrap", "secrets"),
                [{"source": "outcomeprobe-control-api-token"}],
            ),
            (
                "forbidden top-level secret",
                ("secrets",),
                {"outcomeprobe-control-api-token": {}},
            ),
            (
                "forbidden user override",
                ("services", "outcomeprobe-bootstrap", "user"),
                "0:0",
            ),
            (
                "read-only root",
                ("services", "outcomeprobe-bootstrap", "read_only"),
                False,
            ),
            (
                "tmpfs boundary",
                ("services", "outcomeprobe-bootstrap", "tmpfs"),
                [
                    "/tmp:rw,noexec,nosuid,nodev,size=16m,mode=1777",
                    "/run/secrets:rw,noexec,nosuid,nodev,size=64k",
                ],
            ),
            (
                "capability boundary",
                ("services", "outcomeprobe-bootstrap", "cap_drop"),
                ["NET_RAW"],
            ),
            (
                "no-new-privileges",
                ("services", "outcomeprobe-bootstrap", "security_opt"),
                ["no-new-privileges:false"],
            ),
            (
                "PID limit",
                ("services", "outcomeprobe-bootstrap", "pids_limit"),
                1024,
            ),
            (
                "restart policy",
                ("services", "outcomeprobe-bootstrap", "restart"),
                "on-failure",
            ),
        )

        for label, path, replacement in mutations:
            with self.subTest(label=label):
                config = copy.deepcopy(rendered_config(valid_env_values()))
                target: object = config
                for part in path[:-1]:
                    target = target[part]  # type: ignore[index]
                target[path[-1]] = replacement  # type: ignore[index]
                with self.assertRaises(VERIFIER.ContractError):
                    VERIFIER.verify_rendered_config(config, valid_env_values())

    def test_rendered_contract_rejects_non_loopback_and_database_host_port(self) -> None:
        non_loopback = rendered_config(valid_env_values())
        non_loopback["services"]["outcomeprobe-server"]["ports"][0][
            "host_ip"
        ] = "0.0.0.0"
        with self.assertRaisesRegex(VERIFIER.ContractError, "loopback"):
            VERIFIER.verify_rendered_config(non_loopback, valid_env_values())

        database_port = rendered_config(valid_env_values())
        database_port["services"]["postgres"]["ports"] = [
            port("127.0.0.1", "5432", 5432)
        ]
        with self.assertRaisesRegex(VERIFIER.ContractError, "postgres"):
            VERIFIER.verify_rendered_config(database_port, valid_env_values())

        for service, target in (
            ("reference-consumer-vulnerable", 8091),
            ("reference-consumer-resilient", 8092),
        ):
            with self.subTest(service=service):
                consumer_port = rendered_config(valid_env_values())
                consumer_port["services"][service]["ports"] = [
                    port("127.0.0.1", str(target), target)
                ]
                with self.assertRaisesRegex(
                    VERIFIER.ContractError, "publishes a host port"
                ):
                    VERIFIER.verify_rendered_config(
                        consumer_port, valid_env_values()
                    )

    def test_rendered_consumer_provider_urls_reject_localhost(self) -> None:
        for service in (
            "reference-consumer-vulnerable",
            "reference-consumer-resilient",
        ):
            for key, endpoint in (
                ("OUTCOMEPROBE_EMULATOR_BASE_URL", "mockpay"),
                ("OUTCOMEPROBE_BOOKING_EMULATOR_BASE_URL", "mockbooking"),
            ):
                with self.subTest(service=service, key=key):
                    config = rendered_config(valid_env_values())
                    config["services"][service]["environment"][key] = (
                        f"http://localhost:8080/emulator/{endpoint}"
                    )
                    with self.assertRaisesRegex(
                        VERIFIER.ContractError, "rendered runtime value mismatch"
                    ):
                        VERIFIER.verify_rendered_config(
                            config, valid_env_values()
                        )

    def test_rendered_runtime_mismatch_does_not_leak_actual_value(self) -> None:
        config = rendered_config(valid_env_values())
        config["services"]["outcomeprobe-server"]["environment"][
            "OUTCOMEPROBE_DB_PASSWORD"
        ] = "wrong-" + SECRET_SENTINEL
        with self.assertRaises(VERIFIER.ContractError) as raised:
            VERIFIER.verify_rendered_config(config, valid_env_values())
        self.assertNotIn(SECRET_SENTINEL, str(raised.exception))

    def test_docker_render_checks_each_missing_and_empty_required_value(self) -> None:
        values = valid_env_values()
        negative_states: set[tuple[str, str]] = set()
        override_seen = False
        profile_seen = False

        def fake_runner(command: list[str], **_: object) -> subprocess.CompletedProcess[str]:
            nonlocal override_seen, profile_seen
            env_path = pathlib.Path(command[command.index("--env-file") + 1])
            rendered_values = VERIFIER.parse_env_example(env_path)
            for key in VERIFIER.REQUIRED_RENDER_KEYS:
                if key not in rendered_values:
                    negative_states.add((key, "missing"))
                    return subprocess.CompletedProcess(
                        command, 17, "", key + " required " + SECRET_SENTINEL
                    )
                if rendered_values[key] == "":
                    negative_states.add((key, "empty"))
                    return subprocess.CompletedProcess(
                        command, 17, "", key + " required " + SECRET_SENTINEL
                    )

            compose_count = command.count("-f")
            source = None
            if compose_count == 2:
                override_seen = True
                source = pathlib.Path(rendered_values[VERIFIER.SECRET_HOST_KEY])
            include_bootstrap = "--profile" in command
            profile_seen = profile_seen or include_bootstrap
            config = rendered_config(
                rendered_values,
                source,
                include_bootstrap=include_bootstrap,
            )
            return subprocess.CompletedProcess(
                command, 0, json.dumps(config), SECRET_SENTINEL
            )

        VERIFIER.verify_docker_contract(
            values,
            self.compose,
            self.override,
            runner=fake_runner,
        )

        self.assertTrue(override_seen)
        self.assertTrue(profile_seen)
        self.assertEqual(
            set(VERIFIER.REQUIRED_RENDER_KEYS),
            {
                "OUTCOMEPROBE_DB_PASSWORD",
                "OUTCOMEPROBE_DEMO_CALLBACK_SECRET",
                "OUTCOMEPROBE_CONTROL_API_TOKEN",
            },
        )
        self.assertEqual(
            negative_states,
            {
                (key, state)
                for key in VERIFIER.REQUIRED_RENDER_KEYS
                for state in ("missing", "empty")
            },
        )

    def test_compose_stderr_and_rendered_secret_are_not_reported(self) -> None:
        def failing_runner(
            command: list[str], **_: object
        ) -> subprocess.CompletedProcess[str]:
            return subprocess.CompletedProcess(
                command,
                19,
                '{"secret":"' + SECRET_SENTINEL + '"}',
                "failure includes " + SECRET_SENTINEL,
            )

        with self.assertRaises(VERIFIER.ContractError) as raised:
            VERIFIER.verify_docker_contract(
                valid_env_values(),
                self.compose,
                self.override,
                runner=failing_runner,
            )
        self.assertNotIn(SECRET_SENTINEL, str(raised.exception))

    def test_static_only_cli_never_invokes_docker(self) -> None:
        stdout = io.StringIO()
        stderr = io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            result = VERIFIER.main(
                [
                    "--env-example",
                    str(self.env),
                    "--compose-file",
                    str(self.compose),
                    "--secrets-compose-file",
                    str(self.override),
                    "--static-only",
                    "--docker-command",
                    "definitely-not-a-real-docker-executable",
                ]
            )
        self.assertEqual(result, 0, stderr.getvalue())
        self.assertIn("PASS (static)", stdout.getvalue())
        self.assertNotIn(SECRET_SENTINEL, stdout.getvalue() + stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
