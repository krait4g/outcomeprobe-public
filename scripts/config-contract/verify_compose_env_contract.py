#!/usr/bin/env python3
"""Verify the public Compose environment contract without exposing secret values."""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import re
import secrets
import subprocess
import sys
import tempfile
from collections.abc import Callable, Iterable, Mapping, Sequence
from typing import Any


ACTIVE_ENV_KEYS = frozenset(
    {
        "OUTCOMEPROBE_SERVER_PORT",
        "OUTCOMEPROBE_WEB_PORT",
        "OUTCOMEPROBE_DB_NAME",
        "OUTCOMEPROBE_DB_USER",
        "OUTCOMEPROBE_DB_PASSWORD",
        "OUTCOMEPROBE_DEMO_CALLBACK_SECRET",
        "OUTCOMEPROBE_CONTROL_API_TOKEN",
        "OUTCOMEPROBE_RETENTION_DAYS",
        "OUTCOMEPROBE_MAX_CONCURRENT_RUNS",
        "OUTCOMEPROBE_DEMO_PROFILE_SEEDING_ENABLED",
        "OUTCOMEPROBE_EMULATOR_TOKEN",
        "OUTCOMEPROBE_LOG_LEVEL",
    }
)

ALLOWED_EMPTY_ACTIVE_KEYS = frozenset(
    {
        "OUTCOMEPROBE_DB_PASSWORD",
        "OUTCOMEPROBE_DEMO_CALLBACK_SECRET",
        "OUTCOMEPROBE_CONTROL_API_TOKEN",
        "OUTCOMEPROBE_EMULATOR_TOKEN",
    }
)

REQUIRED_RENDER_KEYS = (
    "OUTCOMEPROBE_DB_PASSWORD",
    "OUTCOMEPROBE_DEMO_CALLBACK_SECRET",
    "OUTCOMEPROBE_CONTROL_API_TOKEN",
)

SECRET_HOST_KEY = "OUTCOMEPROBE_SECRETS_HOST_DIRECTORY"
SECRET_CONTAINER_DIRECTORY = "/run/outcomeprobe-secrets"
PAYMENT_EMULATOR_URL = "http://outcomeprobe-server:8080/emulator/mockpay"
BOOKING_EMULATOR_URL = "http://outcomeprobe-server:8080/emulator/mockbooking"
EXPECTED_SERVICES = frozenset(
    {
        "postgres",
        "outcomeprobe-server",
        "outcomeprobe-web",
        "reference-consumer-vulnerable",
        "reference-consumer-resilient",
        "outcomeprobe-bootstrap",
    }
)
DEFAULT_SERVICES = EXPECTED_SERVICES - {"outcomeprobe-bootstrap"}
BOOTSTRAP_SERVICE = "outcomeprobe-bootstrap"
BOOTSTRAP_DOCKERFILE = "docker/bootstrap.Dockerfile"
BOOTSTRAP_SERVER_URL = "http://outcomeprobe-server:8080"
BOOTSTRAP_USER = "10001:10001"
BOOTSTRAP_PROFILE = "tools"
BOOTSTRAP_TOKEN_ENVIRONMENT = "OUTCOMEPROBE_CONTROL_API_TOKEN"
BOOTSTRAP_TOKEN_INTERPOLATION = (
    "${OUTCOMEPROBE_CONTROL_API_TOKEN:"
    "?OUTCOMEPROBE_CONTROL_API_TOKEN must be set}"
)
BOOTSTRAP_TMPFS = ("/tmp:rw,noexec,nosuid,nodev,size=16m,mode=1777",)

_ENV_KEY_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
_INTERPOLATION_RE = re.compile(
    r"\$\{(?P<key>[A-Za-z_][A-Za-z0-9_]*)(?P<suffix>[^}]*)\}"
)


class ContractError(RuntimeError):
    """A contract failure whose message is safe to print."""


def _read_text(path: pathlib.Path, label: str) -> str:
    try:
        return path.read_text(encoding="utf-8-sig")
    except (OSError, UnicodeError) as exc:
        raise ContractError(f"cannot read {label}") from exc


def _decode_env_value(raw: str, key: str) -> str:
    value = raw.strip()
    if not value:
        return ""
    if value.startswith("'") and value.endswith("'") and len(value) >= 2:
        return value[1:-1]
    if value.startswith('"') and value.endswith('"') and len(value) >= 2:
        try:
            decoded = json.loads(value)
        except (TypeError, ValueError) as exc:
            raise ContractError(f"invalid quoted value for {key}") from exc
        if not isinstance(decoded, str):
            raise ContractError(f"invalid quoted value for {key}")
        return decoded
    # Compose treats a whitespace-delimited # as an inline comment.
    value = re.split(r"\s+#", value, maxsplit=1)[0].rstrip()
    return value


def parse_env_example(path: pathlib.Path) -> dict[str, str]:
    """Parse active dotenv assignments, returning values only to in-process checks."""

    values: dict[str, str] = {}
    for line_number, raw_line in enumerate(
        _read_text(path, "environment example").splitlines(), start=1
    ):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[7:].lstrip()
        if "=" not in line:
            raise ContractError(
                f"invalid active environment assignment at line {line_number}"
            )
        key, raw_value = line.split("=", maxsplit=1)
        key = key.strip()
        if not _ENV_KEY_RE.fullmatch(key):
            raise ContractError(
                f"invalid active environment key at line {line_number}"
            )
        if key in values:
            raise ContractError(f"duplicate active environment key: {key}")
        values[key] = _decode_env_value(raw_value, key)
    return values


def _verify_active_keys(
    values: Mapping[str, str], expected_keys: frozenset[str]
) -> None:
    actual = frozenset(values)
    missing = sorted(expected_keys - actual)
    extra = sorted(actual - expected_keys)
    if missing:
        raise ContractError("missing active environment keys: " + ", ".join(missing))
    if extra:
        raise ContractError("unsupported active environment keys: " + ", ".join(extra))
    empty = sorted(
        key
        for key in expected_keys - ALLOWED_EMPTY_ACTIVE_KEYS
        if values[key] == ""
    )
    if empty:
        raise ContractError("empty active environment values: " + ", ".join(empty))
    populated_examples = sorted(
        key for key in REQUIRED_RENDER_KEYS if values.get(key, "") != ""
    )
    if populated_examples:
        raise ContractError(
            "public environment example must leave secrets empty: "
            + ", ".join(populated_examples)
        )
    if values.get("OUTCOMEPROBE_DEMO_PROFILE_SEEDING_ENABLED") != "false":
        raise ContractError("public Quick Start must disable demo profile seeding")


def _source_service_names(compose_text: str) -> frozenset[str]:
    """Return the exact top-level service inventory from Compose source."""

    names: list[str] = []
    in_services = False
    for line in compose_text.splitlines():
        if re.fullmatch(r"services:\s*(?:#.*)?", line):
            in_services = True
            continue
        if in_services and line and not line.startswith((" ", "\t", "#")):
            break
        match = re.fullmatch(r"  ([A-Za-z0-9_.-]+):\s*(?:#.*)?", line)
        if in_services and match:
            names.append(match.group(1))
    if len(names) != len(set(names)):
        raise ContractError("duplicate base Compose service name")
    return frozenset(names)


def _source_section(source: str, key: str, indent: int, label: str) -> str:
    """Extract one indentation-delimited YAML mapping or sequence body."""

    prefix = " " * indent
    header = re.compile(rf"^{re.escape(prefix + key)}:\s*(?:#.*)?$")
    lines = source.splitlines()
    matches = [index for index, line in enumerate(lines) if header.fullmatch(line)]
    if len(matches) != 1:
        raise ContractError(f"{label} section count mismatch")
    start = matches[0] + 1
    end = len(lines)
    for index in range(start, len(lines)):
        line = lines[index]
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if len(line) - len(line.lstrip()) <= indent:
            end = index
            break
    return "\n".join(lines[start:end])


def _source_scalar(value: str) -> str:
    """Normalize a simple source scalar without interpreting secret values."""

    normalized = re.split(r"\s+#", value.strip(), maxsplit=1)[0].rstrip()
    if len(normalized) >= 2 and normalized[0] == normalized[-1] \
            and normalized[0] in ("'", '"'):
        return normalized[1:-1]
    return normalized


def _source_direct_mapping(
    source: str, indent: int, label: str
) -> dict[str, str]:
    """Parse direct scalar/mapping keys from a previously bounded source section."""

    prefix = " " * indent
    result: dict[str, str] = {}
    for line in source.splitlines():
        match = re.fullmatch(
            rf"{re.escape(prefix)}([A-Za-z0-9_.-]+):\s*(.*?)\s*", line
        )
        if match is None:
            continue
        key = match.group(1)
        if key in result:
            raise ContractError(f"duplicate {label} key: {key}")
        result[key] = _source_scalar(match.group(2))
    return result


def _source_scalar_list(
    source: str, indent: int, label: str
) -> tuple[str, ...]:
    """Parse a simple YAML sequence and reject nested or mapping entries."""

    prefix = " " * indent
    values: list[str] = []
    for line in source.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        match = re.fullmatch(rf"{re.escape(prefix)}-\s+(.+?)\s*", line)
        if match is None:
            raise ContractError(f"invalid {label} sequence")
        values.append(_source_scalar(match.group(1)))
    return tuple(values)


def _verify_source_dependencies(
    block: str, service: str, expected: Mapping[str, str]
) -> None:
    """Verify required Compose dependency names and completion conditions."""

    section = _source_section(block, "depends_on", 4, f"{service} depends_on")
    dependencies = _source_direct_mapping(section, 6, f"{service} dependency")
    if frozenset(dependencies) != frozenset(expected):
        raise ContractError(f"dependency set mismatch for {service}")
    for dependency, condition in expected.items():
        detail = _source_section(
            section, dependency, 6, f"{service} dependency {dependency}"
        )
        values = _source_direct_mapping(
            detail, 8, f"{service} dependency {dependency}"
        )
        if values.get("condition") != condition:
            raise ContractError(
                f"dependency condition mismatch for {service}: {dependency}"
            )
        if values.get("required", "true") != "true" or not set(values).issubset(
            {"condition", "required"}
        ):
            raise ContractError(
                f"dependency is not strictly required for {service}: {dependency}"
            )


def _verify_source_alias(block: str, service: str, expected_alias: str) -> None:
    """Bind one reference consumer to its canonical golden-path DNS alias."""

    networks = _source_section(block, "networks", 4, f"{service} networks")
    network_names = _source_direct_mapping(networks, 6, f"{service} network")
    if frozenset(network_names) != frozenset({"app"}):
        raise ContractError(f"network assignment mismatch for {service}")
    app = _source_section(networks, "app", 6, f"{service} app network")
    app_mapping = _source_direct_mapping(app, 8, f"{service} app network")
    if frozenset(app_mapping) != frozenset({"aliases"}):
        raise ContractError(f"network alias mapping mismatch for {service}")
    aliases = _source_scalar_list(
        _source_section(app, "aliases", 8, f"{service} aliases"),
        10,
        f"{service} aliases",
    )
    if aliases != (expected_alias,):
        raise ContractError(f"network alias mismatch for {service}")


def _service_block(compose_text: str, service: str, label: str) -> str:
    lines = compose_text.splitlines()
    in_services = False
    start: int | None = None
    for index, line in enumerate(lines):
        if re.fullmatch(r"services:\s*(?:#.*)?", line):
            in_services = True
            continue
        if in_services and line and not line.startswith((" ", "\t", "#")):
            if start is not None:
                return "\n".join(lines[start:index])
            break
        match = re.fullmatch(r"  ([A-Za-z0-9_.-]+):\s*(?:#.*)?", line)
        if in_services and match:
            if start is not None:
                return "\n".join(lines[start:index])
            if match.group(1) == service:
                start = index
    if start is not None:
        return "\n".join(lines[start:])
    raise ContractError(f"missing {label} service: {service}")


def _require_pattern(text: str, pattern: str, failure: str) -> None:
    if re.search(pattern, text, flags=re.MULTILINE) is None:
        raise ContractError(failure)


def _source_service_networks(block: str, service: str) -> frozenset[str]:
    lines = block.splitlines()
    start: int | None = None
    for index, line in enumerate(lines):
        if re.fullmatch(r"    networks:\s*(?:#.*)?", line):
            start = index + 1
            break
    if start is None:
        raise ContractError(f"network assignment is absent for {service}")
    names: set[str] = set()
    for line in lines[start:]:
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if len(line) - len(line.lstrip()) <= 4:
            break
        list_match = re.fullmatch(
            r"\s{6}-\s*([A-Za-z0-9_.-]+)\s*(?:#.*)?", line
        )
        map_match = re.fullmatch(
            r"\s{6}([A-Za-z0-9_.-]+):\s*(?:#.*)?", line
        )
        match = list_match or map_match
        if match:
            names.add(match.group(1))
    return frozenset(names)


def _top_level_block(source: str, key: str) -> str:
    lines = source.splitlines()
    start: int | None = None
    for index, line in enumerate(lines):
        if re.fullmatch(rf"{re.escape(key)}:\s*(?:#.*)?", line):
            start = index
            break
    if start is None:
        raise ContractError(f"missing top-level Compose section: {key}")
    end = len(lines)
    for index in range(start + 1, len(lines)):
        line = lines[index]
        if line and not line.startswith((" ", "\t", "#")):
            end = index
            break
    return "\n".join(lines[start:end])


def _verify_source_network_topology(
    compose_text: str, service_blocks: Mapping[str, str]
) -> None:
    expected = {
        "postgres": frozenset({"data"}),
        "outcomeprobe-server": frozenset({"app", "data"}),
        "outcomeprobe-web": frozenset({"app"}),
        "reference-consumer-vulnerable": frozenset({"app"}),
        "reference-consumer-resilient": frozenset({"app"}),
        BOOTSTRAP_SERVICE: frozenset({"app"}),
    }
    for service, expected_networks in expected.items():
        actual = _source_service_networks(service_blocks[service], service)
        if actual != expected_networks:
            raise ContractError(f"network assignment mismatch for {service}")

    network_block = _top_level_block(compose_text, "networks")
    declared = frozenset(
        match.group(1)
        for line in network_block.splitlines()[1:]
        if (match := re.fullmatch(r"  ([A-Za-z0-9_.-]+):\s*(?:#.*)?", line))
    )
    if declared != frozenset({"app", "data"}):
        raise ContractError("top-level Compose network set mismatch")
    _require_pattern(
        network_block,
        r"^  data:\s*(?:#.*)?$\n(?:^(?:    |\s*$).*$\n?)*?^    internal:\s*true\s*(?:#.*)?$",
        "data network is not internal",
    )

    # The public app network must not itself be marked internal.
    app_start = re.search(r"^  app:\s*(?:#.*)?$", network_block, re.MULTILINE)
    data_start = re.search(r"^  data:\s*(?:#.*)?$", network_block, re.MULTILINE)
    if app_start and data_start:
        app_text = network_block[app_start.start() : data_start.start()]
        if re.search(r"^    internal:\s*true", app_text, re.MULTILINE):
            raise ContractError("app network must remain externally reachable")


def _verify_required_interpolation(compose_text: str) -> None:
    occurrences: dict[str, list[str]] = {key: [] for key in REQUIRED_RENDER_KEYS}
    for match in _INTERPOLATION_RE.finditer(compose_text):
        key = match.group("key")
        if key in occurrences:
            occurrences[key].append(match.group("suffix"))
    for key, suffixes in occurrences.items():
        if not suffixes:
            raise ContractError(f"required interpolation is absent: {key}")
        if any(not suffix.startswith(":?") for suffix in suffixes):
            raise ContractError(
                f"required interpolation accepts a missing or empty value: {key}"
            )


def _verify_bootstrap_source(
    compose_text: str,
    bootstrap: str,
    web: str,
    vulnerable: str,
    resilient: str,
) -> None:
    """Verify the fail-closed one-shot Quick Start bootstrap source contract."""

    build = _source_direct_mapping(
        _source_section(bootstrap, "build", 4, "bootstrap build"),
        6,
        "bootstrap build",
    )
    if build != {"context": ".", "dockerfile": BOOTSTRAP_DOCKERFILE}:
        raise ContractError("bootstrap build must use the exact Dockerfile")

    if re.search(r"^    (?:ports|env_file):\s*", bootstrap, re.MULTILINE):
        raise ContractError("bootstrap must not publish ports or load an env file")
    environment = _source_direct_mapping(
        _source_section(bootstrap, "environment", 4, "bootstrap environment"),
        6,
        "bootstrap environment",
    )
    if environment != {
        "OUTCOMEPROBE_SERVER_URL": BOOTSTRAP_SERVER_URL,
        BOOTSTRAP_TOKEN_ENVIRONMENT: BOOTSTRAP_TOKEN_INTERPOLATION,
    }:
        raise ContractError("bootstrap environment mapping mismatch")

    profiles = _source_scalar_list(
        _source_section(bootstrap, "profiles", 4, "bootstrap profiles"),
        6,
        "bootstrap profiles",
    )
    if profiles != (BOOTSTRAP_PROFILE,):
        raise ContractError("bootstrap profile must be exactly tools")

    bootstrap_networks = _source_scalar_list(
        _source_section(bootstrap, "networks", 4, "bootstrap networks"),
        6,
        "bootstrap networks",
    )
    if bootstrap_networks != ("app",):
        raise ContractError("bootstrap must use only the app network")

    _verify_source_dependencies(
        bootstrap,
        BOOTSTRAP_SERVICE,
        {
            "outcomeprobe-server": "service_healthy",
            "reference-consumer-vulnerable": "service_healthy",
            "reference-consumer-resilient": "service_healthy",
        },
    )
    _verify_source_dependencies(
        web,
        "outcomeprobe-web",
        {"outcomeprobe-server": "service_healthy"},
    )
    _verify_source_alias(
        vulnerable, "reference-consumer-vulnerable", "rr-vulnerable"
    )
    _verify_source_alias(
        resilient, "reference-consumer-resilient", "rr-resilient"
    )

    service_values = _source_direct_mapping(bootstrap, 4, "bootstrap service")
    expected_scalars = {
        "read_only": "true",
        "pids_limit": "128",
        "restart": "no",
    }
    for key, expected in expected_scalars.items():
        if service_values.get(key) != expected:
            raise ContractError(f"bootstrap security value mismatch: {key}")
    if "user" in service_values:
        raise ContractError("bootstrap must preserve the image non-root user")

    tmpfs = _source_scalar_list(
        _source_section(bootstrap, "tmpfs", 4, "bootstrap tmpfs"),
        6,
        "bootstrap tmpfs",
    )
    if tmpfs != BOOTSTRAP_TMPFS:
        raise ContractError("bootstrap tmpfs boundary mismatch")
    cap_drop = _source_scalar_list(
        _source_section(bootstrap, "cap_drop", 4, "bootstrap cap_drop"),
        6,
        "bootstrap cap_drop",
    )
    if cap_drop != ("ALL",):
        raise ContractError("bootstrap must drop every Linux capability")
    security_opt = _source_scalar_list(
        _source_section(bootstrap, "security_opt", 4, "bootstrap security_opt"),
        6,
        "bootstrap security_opt",
    )
    if security_opt != ("no-new-privileges:true",):
        raise ContractError("bootstrap no-new-privileges boundary mismatch")

    if re.search(r"^    secrets:\s*", bootstrap, re.MULTILINE):
        raise ContractError("bootstrap must not use a Compose secret grant")
    if re.search(r"^secrets:\s*", compose_text, re.MULTILINE):
        raise ContractError("base Compose must not declare a top-level secret")


def _verify_base_source(compose_text: str) -> None:
    _verify_required_interpolation(compose_text)
    if _source_service_names(compose_text) != EXPECTED_SERVICES:
        raise ContractError("base Compose service inventory mismatch")
    postgres = _service_block(compose_text, "postgres", "base Compose")
    server = _service_block(compose_text, "outcomeprobe-server", "base Compose")
    web = _service_block(compose_text, "outcomeprobe-web", "base Compose")
    vulnerable = _service_block(
        compose_text, "reference-consumer-vulnerable", "base Compose"
    )
    resilient = _service_block(
        compose_text, "reference-consumer-resilient", "base Compose"
    )
    bootstrap = _service_block(compose_text, BOOTSTRAP_SERVICE, "base Compose")
    service_blocks = {
        "postgres": postgres,
        "outcomeprobe-server": server,
        "outcomeprobe-web": web,
        "reference-consumer-vulnerable": vulnerable,
        "reference-consumer-resilient": resilient,
        BOOTSTRAP_SERVICE: bootstrap,
    }

    for block, service in (
        (postgres, "postgres"),
        (vulnerable, "reference-consumer-vulnerable"),
        (resilient, "reference-consumer-resilient"),
    ):
        if re.search(r"^    ports:\s*(?:#.*)?$", block, flags=re.MULTILINE):
            raise ContractError(f"{service} must not publish a host port")

    expected_ports = (
        (
            server,
            "outcomeprobe-server",
            "127.0.0.1:${OUTCOMEPROBE_SERVER_PORT:-8080}:8080",
        ),
        (
            web,
            "outcomeprobe-web",
            "127.0.0.1:${OUTCOMEPROBE_WEB_PORT:-3000}:3000",
        ),
    )
    for block, service, expected in expected_ports:
        ports = _source_scalar_list(
            _source_section(block, "ports", 4, f"{service} ports"),
            6,
            f"{service} ports",
        )
        if ports != (expected,):
            raise ContractError(
                f"{service} must publish exactly one loopback port"
            )

    _require_pattern(
        server,
        r"^      OUTCOMEPROBE_DB_HOST:\s*['\"]?postgres['\"]?\s*(?:#.*)?$",
        "server database host is not fixed to the Compose service",
    )
    _require_pattern(
        server,
        r"^      OUTCOMEPROBE_DB_PORT:\s*['\"]?5432['\"]?\s*(?:#.*)?$",
        "server database port is not fixed to the container port",
    )

    required_server_mappings = {
        "OUTCOMEPROBE_DB_NAME": "OUTCOMEPROBE_DB_NAME",
        "OUTCOMEPROBE_DB_USER": "OUTCOMEPROBE_DB_USER",
        "OUTCOMEPROBE_DB_PASSWORD": "OUTCOMEPROBE_DB_PASSWORD",
        "OUTCOMEPROBE_DEMO_CALLBACK_SECRET": "OUTCOMEPROBE_DEMO_CALLBACK_SECRET",
        "OUTCOMEPROBE_CONTROL_API_TOKEN": "OUTCOMEPROBE_CONTROL_API_TOKEN",
        "OUTCOMEPROBE_RETENTION_DAYS": "OUTCOMEPROBE_RETENTION_DAYS",
        "OUTCOMEPROBE_MAX_CONCURRENT_RUNS": "OUTCOMEPROBE_MAX_CONCURRENT_RUNS",
        "OUTCOMEPROBE_DEMO_PROFILE_SEEDING_ENABLED": (
            "OUTCOMEPROBE_DEMO_PROFILE_SEEDING_ENABLED"
        ),
        "OUTCOMEPROBE_EMULATOR_TOKEN": "OUTCOMEPROBE_EMULATOR_TOKEN",
        "OUTCOMEPROBE_LOG_LEVEL": "OUTCOMEPROBE_LOG_LEVEL",
    }
    for target, source in required_server_mappings.items():
        _require_pattern(
            server,
            rf"^      {re.escape(target)}:\s*\$\{{{re.escape(source)}(?:[^}}]*)\}}\s*(?:#.*)?$",
            f"server runtime mapping is absent: {target}",
        )
    _require_pattern(
        server,
        r"^      OUTCOMEPROBE_DEMO_PROFILE_SEEDING_ENABLED:\s*"
        r"\$\{OUTCOMEPROBE_DEMO_PROFILE_SEEDING_ENABLED:-false\}\s*(?:#.*)?$",
        "server demo profile seeding must default to false",
    )

    for block, service in (
        (postgres, "postgres"),
        (vulnerable, "reference-consumer-vulnerable"),
        (resilient, "reference-consumer-resilient"),
    ):
        if service == "postgres":
            mappings = {
                "POSTGRES_DB": "OUTCOMEPROBE_DB_NAME",
                "POSTGRES_USER": "OUTCOMEPROBE_DB_USER",
                "POSTGRES_PASSWORD": "OUTCOMEPROBE_DB_PASSWORD",
            }
        else:
            mappings = {
                "OUTCOMEPROBE_DEMO_CALLBACK_SECRET": (
                    "OUTCOMEPROBE_DEMO_CALLBACK_SECRET"
                )
            }
        for target, source in mappings.items():
            _require_pattern(
                block,
                rf"^      {re.escape(target)}:\s*\$\{{{re.escape(source)}(?:[^}}]*)\}}\s*(?:#.*)?$",
                f"runtime mapping is absent for {service}: {target}",
            )
        if service != "postgres":
            for target, expected in (
                ("OUTCOMEPROBE_EMULATOR_BASE_URL", PAYMENT_EMULATOR_URL),
                ("OUTCOMEPROBE_BOOKING_EMULATOR_BASE_URL", BOOKING_EMULATOR_URL),
            ):
                _require_pattern(
                    block,
                    rf"^      {target}:\s*['\"]?{re.escape(expected)}['\"]?\s*(?:#.*)?$",
                    f"fixed emulator URL is invalid for {service}: {target}",
                )
            _require_pattern(
                block,
                r"^      OUTCOMEPROBE_EMULATOR_TOKEN:\s*\$\{OUTCOMEPROBE_EMULATOR_TOKEN(?:[^}]*)\}\s*(?:#.*)?$",
                f"emulator token mapping is absent for {service}",
            )

    _verify_bootstrap_source(
        compose_text, bootstrap, web, vulnerable, resilient
    )
    _verify_source_network_topology(compose_text, service_blocks)


def _verify_override_source(override_text: str) -> None:
    server = _service_block(
        override_text, "outcomeprobe-server", "secret-directory override"
    )
    host_interpolations = [
        match.group("suffix")
        for match in _INTERPOLATION_RE.finditer(server)
        if match.group("key") == SECRET_HOST_KEY
    ]
    if not host_interpolations or any(
        not suffix.startswith(":?") for suffix in host_interpolations
    ):
        raise ContractError("secret host directory is not required by the override")
    _require_pattern(
        server,
        rf"^\s+target:\s*['\"]?{re.escape(SECRET_CONTAINER_DIRECTORY)}['\"]?\s*(?:#.*)?$",
        "secret-directory override target is invalid",
    )
    _require_pattern(
        server,
        r"^\s+read_only:\s*true\s*(?:#.*)?$",
        "secret-directory override is not read-only",
    )
    _require_pattern(
        server,
        rf"^\s+OUTCOMEPROBE_SECRETS_DIRECTORY:\s*['\"]?{re.escape(SECRET_CONTAINER_DIRECTORY)}['\"]?\s*(?:#.*)?$",
        "server secret-directory runtime mapping is invalid",
    )


def verify_static_contract(
    env_example: pathlib.Path,
    compose_file: pathlib.Path,
    secrets_compose_file: pathlib.Path,
    *,
    expected_keys: frozenset[str] = ACTIVE_ENV_KEYS,
    bootstrap_dockerfile: pathlib.Path | None = None,
) -> dict[str, str]:
    """Verify source-level invariants and return parsed example values."""

    values = parse_env_example(env_example)
    _verify_active_keys(values, expected_keys)
    _verify_base_source(_read_text(compose_file, "base Compose file"))
    _verify_override_source(
        _read_text(secrets_compose_file, "secret-directory Compose override")
    )
    dockerfile = bootstrap_dockerfile
    if dockerfile is None:
        dockerfile = compose_file.resolve().parent / BOOTSTRAP_DOCKERFILE
    if dockerfile.is_file():
        dockerfile_text = _read_text(dockerfile, "bootstrap Dockerfile")
        user_directives = re.findall(
            r"^USER\s+([^\s#]+)\s*(?:#.*)?$", dockerfile_text, re.MULTILINE
        )
        if user_directives != [BOOTSTRAP_USER]:
            raise ContractError("bootstrap image user must be exactly 10001:10001")
    return values


def verify_application_property_contract(
    server_application_file: pathlib.Path,
    reference_consumer_application_file: pathlib.Path,
) -> None:
    """Bind the public Compose keys to the exact Spring property placeholders."""

    server = _read_text(server_application_file, "server application configuration")
    server_patterns = {
        "database password": r"^\s+password:\s*\$\{OUTCOMEPROBE_DB_PASSWORD\}\s*$",
        "control API token": (
            r"^\s+control-api-token:\s*\$\{OUTCOMEPROBE_CONTROL_API_TOKEN\}\s*$"
        ),
        "retention": (
            r"^\s+days:\s*\$\{OUTCOMEPROBE_RETENTION_DAYS:14\}\s*$"
        ),
        "maximum concurrent runs": (
            r"^\s+max-concurrent-runs:\s*"
            r"\$\{OUTCOMEPROBE_MAX_CONCURRENT_RUNS:10\}\s*$"
        ),
        "emulator token": (
            r"^\s+token:\s*\$\{OUTCOMEPROBE_EMULATOR_TOKEN:\}\s*$"
        ),
        "demo profile opt-in": (
            r"^\s+profile-seeding-enabled:\s*"
            r"\$\{OUTCOMEPROBE_DEMO_PROFILE_SEEDING_ENABLED:false\}\s*$"
        ),
        "log level": (
            r"^\s+io\.outcomeprobe:\s*\$\{OUTCOMEPROBE_LOG_LEVEL:INFO\}\s*$"
        ),
    }
    for label, pattern in server_patterns.items():
        _require_pattern(
            server,
            pattern,
            f"server Spring property mapping is invalid: {label}",
        )

    reference = _read_text(
        reference_consumer_application_file,
        "reference consumer application configuration",
    )
    reference_patterns = {
        "payment emulator URL": (
            r"^\s+base-url:\s*\$\{OUTCOMEPROBE_EMULATOR_BASE_URL:"
            r"http://localhost:8080/emulator/mockpay\}\s*$"
        ),
        "booking emulator URL": (
            r"^\s+base-url:\s*\$\{OUTCOMEPROBE_BOOKING_EMULATOR_BASE_URL:"
            r"http://localhost:8080/emulator/mockbooking\}\s*$"
        ),
        "emulator token": (
            r"^\s+emulator-token:\s*\$\{OUTCOMEPROBE_EMULATOR_TOKEN:\}\s*$"
        ),
        "callback secret": (
            r"^\s+signing-secret:\s*\$\{OUTCOMEPROBE_DEMO_CALLBACK_SECRET\}\s*$"
        ),
    }
    for label, pattern in reference_patterns.items():
        _require_pattern(
            reference,
            pattern,
            f"reference consumer Spring property mapping is invalid: {label}",
        )
    for dead_property in ("connect-timeout:", "read-timeout:", "demo-mode:"):
        if dead_property in reference:
            raise ContractError(
                "unused reference consumer property remains: " + dead_property[:-1]
            )


def _environment_map(service: Mapping[str, Any], service_name: str) -> dict[str, str]:
    raw = service.get("environment", {})
    if isinstance(raw, Mapping):
        return {str(key): "" if value is None else str(value) for key, value in raw.items()}
    if isinstance(raw, list):
        result: dict[str, str] = {}
        for item in raw:
            if not isinstance(item, str) or "=" not in item:
                raise ContractError(f"invalid rendered environment for {service_name}")
            key, value = item.split("=", maxsplit=1)
            result[key] = value
        return result
    raise ContractError(f"invalid rendered environment for {service_name}")


def _rendered_service(config: Mapping[str, Any], name: str) -> Mapping[str, Any]:
    services = config.get("services")
    if not isinstance(services, Mapping):
        raise ContractError("rendered Compose config has no services map")
    service = services.get(name)
    if not isinstance(service, Mapping):
        raise ContractError(f"rendered Compose config is missing service: {name}")
    return service


def _rendered_network_names(
    service: Mapping[str, Any], service_name: str
) -> frozenset[str]:
    raw = service.get("networks")
    if isinstance(raw, Mapping):
        return frozenset(str(name) for name in raw)
    if isinstance(raw, list) and all(isinstance(name, str) for name in raw):
        return frozenset(raw)
    raise ContractError(f"invalid rendered network assignment for {service_name}")


def _verify_rendered_network_topology(
    config: Mapping[str, Any], services: Mapping[str, Mapping[str, Any]]
) -> None:
    expected = {
        "postgres": frozenset({"data"}),
        "outcomeprobe-server": frozenset({"app", "data"}),
        "outcomeprobe-web": frozenset({"app"}),
        "reference-consumer-vulnerable": frozenset({"app"}),
        "reference-consumer-resilient": frozenset({"app"}),
    }
    if BOOTSTRAP_SERVICE in services:
        expected[BOOTSTRAP_SERVICE] = frozenset({"app"})
    for service_name, expected_networks in expected.items():
        actual = _rendered_network_names(services[service_name], service_name)
        if actual != expected_networks:
            raise ContractError(
                f"rendered network assignment mismatch for {service_name}"
            )

    networks = config.get("networks")
    if not isinstance(networks, Mapping) or frozenset(networks) != frozenset(
        {"app", "data"}
    ):
        raise ContractError("rendered top-level Compose network set mismatch")
    data = networks.get("data")
    app = networks.get("app")
    if not isinstance(data, Mapping) or data.get("internal") is not True:
        raise ContractError("rendered data network is not internal")
    if isinstance(app, Mapping) and app.get("internal") is True:
        raise ContractError("rendered app network must remain externally reachable")


def _verify_value(
    actual: Mapping[str, str], key: str, expected: str, service: str
) -> None:
    if actual.get(key) != expected:
        raise ContractError(f"rendered runtime value mismatch for {service}: {key}")


def _verify_port(
    service: Mapping[str, Any],
    service_name: str,
    published: str,
    target: int,
) -> None:
    ports = service.get("ports")
    if not isinstance(ports, list) or len(ports) != 1:
        raise ContractError(f"rendered port count mismatch for {service_name}")
    port = ports[0]
    if not isinstance(port, Mapping):
        raise ContractError(f"invalid rendered port for {service_name}")
    if (
        str(port.get("host_ip")) != "127.0.0.1"
        or str(port.get("published")) != published
        or str(port.get("target")) != str(target)
        or str(port.get("protocol", "tcp")) != "tcp"
    ):
        raise ContractError(f"rendered loopback port mismatch for {service_name}")


def _normal_path(value: str) -> str:
    return os.path.normcase(os.path.normpath(os.path.abspath(value)))


def _verify_secret_override(
    server: Mapping[str, Any], expected_source: pathlib.Path
) -> None:
    volumes = server.get("volumes")
    if not isinstance(volumes, list):
        raise ContractError("rendered secret-directory bind mount is absent")
    candidates = [
        volume
        for volume in volumes
        if isinstance(volume, Mapping)
        and str(volume.get("target")) == SECRET_CONTAINER_DIRECTORY
    ]
    if len(candidates) != 1:
        raise ContractError("rendered secret-directory bind mount count mismatch")
    mount = candidates[0]
    source = mount.get("source")
    if (
        str(mount.get("type")) != "bind"
        or not isinstance(source, str)
        or _normal_path(source) != _normal_path(str(expected_source))
        or mount.get("read_only") is not True
    ):
        raise ContractError("rendered secret-directory bind mount is invalid")
    environment = _environment_map(server, "outcomeprobe-server")
    _verify_value(
        environment,
        "OUTCOMEPROBE_SECRETS_DIRECTORY",
        SECRET_CONTAINER_DIRECTORY,
        "outcomeprobe-server",
    )


def _verify_rendered_dependencies(
    service: Mapping[str, Any], service_name: str, expected: Mapping[str, str]
) -> None:
    """Verify dependency names, conditions, and their required semantics."""

    raw = service.get("depends_on")
    if not isinstance(raw, Mapping) or frozenset(raw) != frozenset(expected):
        raise ContractError(f"rendered dependency set mismatch for {service_name}")
    for dependency, expected_condition in expected.items():
        detail = raw.get(dependency)
        if not isinstance(detail, Mapping):
            raise ContractError(
                f"invalid rendered dependency for {service_name}: {dependency}"
            )
        if (
            detail.get("condition") != expected_condition
            or detail.get("required") is not True
            or detail.get("restart") not in (None, False)
            or not set(detail).issubset({"condition", "required", "restart"})
        ):
            raise ContractError(
                f"rendered dependency condition mismatch for {service_name}: "
                f"{dependency}"
            )


def _verify_rendered_alias(
    service: Mapping[str, Any], service_name: str, expected_alias: str
) -> None:
    """Verify the only app-network alias exposed by a reference consumer."""

    networks = service.get("networks")
    if not isinstance(networks, Mapping) or frozenset(networks) != frozenset({"app"}):
        raise ContractError(f"rendered network assignment mismatch for {service_name}")
    app = networks.get("app")
    if not isinstance(app, Mapping) or app.get("aliases") != [expected_alias]:
        raise ContractError(f"rendered network alias mismatch for {service_name}")
    if not set(app).issubset({"aliases"}):
        raise ContractError(f"rendered app network options mismatch for {service_name}")


def _verify_rendered_bootstrap(
    bootstrap: Mapping[str, Any],
    env_values: Mapping[str, str],
) -> None:
    """Verify the opt-in tools-profile bootstrap's rendered isolation contract."""

    build = bootstrap.get("build")
    if (
        not isinstance(build, Mapping)
        or build.get("dockerfile") != BOOTSTRAP_DOCKERFILE
        or not isinstance(build.get("context"), str)
        or not build.get("context")
    ):
        raise ContractError("rendered bootstrap Dockerfile mismatch")
    if bootstrap.get("ports") not in (None, []):
        raise ContractError("rendered bootstrap publishes a host port")
    if bootstrap.get("env_file") not in (None, []):
        raise ContractError("rendered bootstrap loads an env file")
    environment = _environment_map(bootstrap, BOOTSTRAP_SERVICE)
    if environment != {
        "OUTCOMEPROBE_SERVER_URL": BOOTSTRAP_SERVER_URL,
        BOOTSTRAP_TOKEN_ENVIRONMENT: env_values[BOOTSTRAP_TOKEN_ENVIRONMENT],
    }:
        raise ContractError("rendered bootstrap environment mismatch")
    if bootstrap.get("profiles") != [BOOTSTRAP_PROFILE]:
        raise ContractError("rendered bootstrap profile must be exactly tools")

    networks = bootstrap.get("networks")
    if (
        not isinstance(networks, Mapping)
        or frozenset(networks) != frozenset({"app"})
        or networks.get("app") not in (None, {})
    ):
        raise ContractError("rendered bootstrap must use only the plain app network")
    _verify_rendered_dependencies(
        bootstrap,
        BOOTSTRAP_SERVICE,
        {
            "outcomeprobe-server": "service_healthy",
            "reference-consumer-vulnerable": "service_healthy",
            "reference-consumer-resilient": "service_healthy",
        },
    )
    if bootstrap.get("secrets") not in (None, []):
        raise ContractError("rendered bootstrap must not use a Compose secret grant")

    if bootstrap.get("user") not in (None, ""):
        raise ContractError("rendered bootstrap overrides the image user")
    if bootstrap.get("read_only") is not True:
        raise ContractError("rendered bootstrap root filesystem is not read-only")
    if bootstrap.get("tmpfs") != list(BOOTSTRAP_TMPFS):
        raise ContractError("rendered bootstrap tmpfs boundary mismatch")
    if bootstrap.get("cap_drop") != ["ALL"]:
        raise ContractError("rendered bootstrap capability boundary mismatch")
    if bootstrap.get("security_opt") != ["no-new-privileges:true"]:
        raise ContractError("rendered bootstrap no-new-privileges boundary mismatch")
    if str(bootstrap.get("pids_limit")) != "128":
        raise ContractError("rendered bootstrap PID limit mismatch")
    if bootstrap.get("restart") != "no":
        raise ContractError("rendered bootstrap restart policy mismatch")


def verify_rendered_config(
    config: Mapping[str, Any],
    env_values: Mapping[str, str],
    *,
    secret_source: pathlib.Path | None = None,
    include_bootstrap: bool = True,
) -> None:
    """Verify a parsed ``docker compose config --format json`` document."""

    services = config.get("services")
    expected_services = EXPECTED_SERVICES if include_bootstrap else DEFAULT_SERVICES
    if not isinstance(services, Mapping) or frozenset(services) != expected_services:
        raise ContractError("rendered Compose service inventory mismatch")

    postgres = _rendered_service(config, "postgres")
    server = _rendered_service(config, "outcomeprobe-server")
    web = _rendered_service(config, "outcomeprobe-web")
    vulnerable = _rendered_service(config, "reference-consumer-vulnerable")
    resilient = _rendered_service(config, "reference-consumer-resilient")
    bootstrap = (
        _rendered_service(config, BOOTSTRAP_SERVICE) if include_bootstrap else None
    )

    for service, service_name in (
        (postgres, "postgres"),
        (vulnerable, "reference-consumer-vulnerable"),
        (resilient, "reference-consumer-resilient"),
    ):
        if service.get("ports") not in (None, []):
            raise ContractError(f"rendered {service_name} publishes a host port")
    _verify_port(
        server,
        "outcomeprobe-server",
        env_values["OUTCOMEPROBE_SERVER_PORT"],
        8080,
    )
    _verify_port(
        web,
        "outcomeprobe-web",
        env_values["OUTCOMEPROBE_WEB_PORT"],
        3000,
    )
    postgres_environment = _environment_map(postgres, "postgres")
    for target, source in (
        ("POSTGRES_DB", "OUTCOMEPROBE_DB_NAME"),
        ("POSTGRES_USER", "OUTCOMEPROBE_DB_USER"),
        ("POSTGRES_PASSWORD", "OUTCOMEPROBE_DB_PASSWORD"),
    ):
        _verify_value(postgres_environment, target, env_values[source], "postgres")

    server_environment = _environment_map(server, "outcomeprobe-server")
    fixed_server_values = {
        "OUTCOMEPROBE_DB_HOST": "postgres",
        "OUTCOMEPROBE_DB_PORT": "5432",
        "OUTCOMEPROBE_DEMO_PROFILE_SEEDING_ENABLED": "false",
    }
    for key, expected in fixed_server_values.items():
        _verify_value(server_environment, key, expected, "outcomeprobe-server")
    for target, source in (
        ("OUTCOMEPROBE_DB_NAME", "OUTCOMEPROBE_DB_NAME"),
        ("OUTCOMEPROBE_DB_USER", "OUTCOMEPROBE_DB_USER"),
        ("OUTCOMEPROBE_DB_PASSWORD", "OUTCOMEPROBE_DB_PASSWORD"),
        (
            "OUTCOMEPROBE_DEMO_CALLBACK_SECRET",
            "OUTCOMEPROBE_DEMO_CALLBACK_SECRET",
        ),
        ("OUTCOMEPROBE_CONTROL_API_TOKEN", "OUTCOMEPROBE_CONTROL_API_TOKEN"),
        ("OUTCOMEPROBE_RETENTION_DAYS", "OUTCOMEPROBE_RETENTION_DAYS"),
        ("OUTCOMEPROBE_MAX_CONCURRENT_RUNS", "OUTCOMEPROBE_MAX_CONCURRENT_RUNS"),
        ("OUTCOMEPROBE_EMULATOR_TOKEN", "OUTCOMEPROBE_EMULATOR_TOKEN"),
        ("OUTCOMEPROBE_LOG_LEVEL", "OUTCOMEPROBE_LOG_LEVEL"),
    ):
        _verify_value(
            server_environment, target, env_values[source], "outcomeprobe-server"
        )

    for service, name in (
        (vulnerable, "reference-consumer-vulnerable"),
        (resilient, "reference-consumer-resilient"),
    ):
        environment = _environment_map(service, name)
        _verify_value(
            environment,
            "OUTCOMEPROBE_DEMO_CALLBACK_SECRET",
            env_values["OUTCOMEPROBE_DEMO_CALLBACK_SECRET"],
            name,
        )

        _verify_value(
            environment,
            "OUTCOMEPROBE_EMULATOR_BASE_URL",
            PAYMENT_EMULATOR_URL,
            name,
        )
        _verify_value(
            environment,
            "OUTCOMEPROBE_BOOKING_EMULATOR_BASE_URL",
            BOOKING_EMULATOR_URL,
            name,
        )
        _verify_value(
            environment,
            "OUTCOMEPROBE_EMULATOR_TOKEN",
            env_values["OUTCOMEPROBE_EMULATOR_TOKEN"],
            name,
        )

    _verify_rendered_alias(
        vulnerable, "reference-consumer-vulnerable", "rr-vulnerable"
    )
    _verify_rendered_alias(
        resilient, "reference-consumer-resilient", "rr-resilient"
    )
    _verify_rendered_dependencies(
        web,
        "outcomeprobe-web",
        {"outcomeprobe-server": "service_healthy"},
    )

    _verify_rendered_network_topology(
        config,
        {
            "postgres": postgres,
            "outcomeprobe-server": server,
            "outcomeprobe-web": web,
            "reference-consumer-vulnerable": vulnerable,
            "reference-consumer-resilient": resilient,
            **({BOOTSTRAP_SERVICE: bootstrap} if bootstrap is not None else {}),
        },
    )

    if config.get("secrets") not in (None, {}):
        raise ContractError("rendered base Compose must not declare a secret")
    if bootstrap is not None:
        _verify_rendered_bootstrap(bootstrap, env_values)

    if secret_source is not None:
        _verify_secret_override(server, secret_source)


def _write_env_file(path: pathlib.Path, values: Mapping[str, str]) -> None:
    lines: list[str] = []
    for key in sorted(values):
        value = values[key]
        if "\x00" in value or "\n" in value or "\r" in value:
            raise ContractError(f"unsafe multiline environment value: {key}")
        lines.append(f"{key}={json.dumps(value, ensure_ascii=True)}")
    try:
        path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    except OSError as exc:
        raise ContractError("cannot create isolated Compose environment") from exc


def _sanitized_process_environment(keys: Iterable[str]) -> dict[str, str]:
    environment = os.environ.copy()
    for key in list(environment):
        if key.startswith("OUTCOMEPROBE_") or key in keys:
            environment.pop(key, None)
    return environment


def _compose_render(
    docker_command: str,
    compose_files: Sequence[pathlib.Path],
    project_directory: pathlib.Path,
    env_file: pathlib.Path,
    *,
    expect_success: bool,
    expected_failure_key: str | None = None,
    active_profiles: Sequence[str] = (),
    runner: Callable[..., subprocess.CompletedProcess[str]],
) -> Mapping[str, Any] | None:
    command = [
        docker_command,
        "compose",
        "--ansi",
        "never",
        "--project-name",
        "outcomeprobe-contract-check",
        "--project-directory",
        str(project_directory),
        "--env-file",
        str(env_file),
    ]
    for compose_file in compose_files:
        command.extend(("-f", str(compose_file)))
    for profile in active_profiles:
        command.extend(("--profile", profile))
    command.extend(("config", "--format", "json"))
    try:
        completed = runner(
            command,
            cwd=project_directory,
            env=_sanitized_process_environment(REQUIRED_RENDER_KEYS),
            capture_output=True,
            text=True,
            encoding="utf-8",
            timeout=60,
            check=False,
        )
    except FileNotFoundError as exc:
        raise ContractError("docker compose is unavailable") from exc
    except subprocess.TimeoutExpired as exc:
        raise ContractError("docker compose render timed out") from exc
    except OSError as exc:
        raise ContractError("docker compose render could not start") from exc

    if not expect_success:
        if completed.returncode == 0:
            raise ContractError("required-value negative Compose render succeeded")
        if expected_failure_key is not None and expected_failure_key not in (
            (completed.stdout or "") + (completed.stderr or "")
        ):
            raise ContractError(
                "negative Compose render failed for an unrelated reason: "
                + expected_failure_key
            )
        return None
    if completed.returncode != 0:
        # Deliberately do not include stdout/stderr: Compose may have rendered secrets.
        raise ContractError("docker compose render failed")
    try:
        parsed = json.loads(completed.stdout)
    except (TypeError, ValueError) as exc:
        raise ContractError("docker compose returned invalid JSON") from exc
    if not isinstance(parsed, Mapping):
        raise ContractError("docker compose returned a non-object JSON document")
    return parsed


def verify_docker_contract(
    env_values: Mapping[str, str],
    compose_file: pathlib.Path,
    secrets_compose_file: pathlib.Path,
    *,
    docker_command: str = "docker",
    runner: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
) -> None:
    """Render Compose and prove positive, negative, and override postconditions."""

    project_directory = compose_file.resolve().parent
    with tempfile.TemporaryDirectory(prefix="outcomeprobe-compose-contract-") as raw_temp:
        temporary = pathlib.Path(raw_temp)
        positive_values = dict(env_values)
        for key in REQUIRED_RENDER_KEYS:
            # The public example intentionally leaves secrets empty. Use values that
            # exist only in this temporary directory to prove the positive render.
            positive_values[key] = "contract-check-" + secrets.token_urlsafe(24)
        positive_env = temporary / "positive.env"
        _write_env_file(positive_env, positive_values)
        rendered = _compose_render(
            docker_command,
            (compose_file.resolve(),),
            project_directory,
            positive_env,
            expect_success=True,
            runner=runner,
        )
        assert rendered is not None
        verify_rendered_config(
            rendered, positive_values, include_bootstrap=False
        )

        profiled_rendered = _compose_render(
            docker_command,
            (compose_file.resolve(),),
            project_directory,
            positive_env,
            expect_success=True,
            active_profiles=("*",),
            runner=runner,
        )
        assert profiled_rendered is not None
        verify_rendered_config(profiled_rendered, positive_values)

        for key in REQUIRED_RENDER_KEYS:
            for state in ("missing", "empty"):
                negative_values = dict(positive_values)
                if state == "missing":
                    negative_values.pop(key, None)
                else:
                    negative_values[key] = ""
                negative_env = temporary / f"negative-{key.lower()}-{state}.env"
                _write_env_file(negative_env, negative_values)
                try:
                    _compose_render(
                        docker_command,
                        (compose_file.resolve(),),
                        project_directory,
                        negative_env,
                        expect_success=False,
                        expected_failure_key=key,
                        runner=runner,
                    )
                except ContractError as exc:
                    if str(exc) == "required-value negative Compose render succeeded":
                        raise ContractError(
                            f"Compose accepts {state} required value: {key}"
                        ) from exc
                    raise

        secret_source = temporary / "secret-source"
        secret_source.mkdir()
        override_values = dict(positive_values)
        override_values[SECRET_HOST_KEY] = secret_source.resolve().as_posix()
        override_env = temporary / "secret-override.env"
        _write_env_file(override_env, override_values)
        override_rendered = _compose_render(
            docker_command,
            (compose_file.resolve(), secrets_compose_file.resolve()),
            project_directory,
            override_env,
            expect_success=True,
            runner=runner,
        )
        assert override_rendered is not None
        verify_rendered_config(
            override_rendered,
            positive_values,
            secret_source=secret_source.resolve(),
            include_bootstrap=False,
        )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--env-example", type=pathlib.Path, default=pathlib.Path(".env.example")
    )
    parser.add_argument(
        "--compose-file", type=pathlib.Path, default=pathlib.Path("compose.yaml")
    )
    parser.add_argument(
        "--secrets-compose-file",
        type=pathlib.Path,
        default=pathlib.Path("compose.secrets-directory.yaml"),
    )
    parser.add_argument(
        "--server-application-file",
        type=pathlib.Path,
        default=pathlib.Path("server/src/main/resources/application.yaml"),
    )
    parser.add_argument(
        "--reference-consumer-application-file",
        type=pathlib.Path,
        default=pathlib.Path(
            "examples/reference-consumer/src/main/resources/application.yaml"
        ),
    )
    parser.add_argument(
        "--static-only",
        action="store_true",
        help="verify source invariants without invoking docker compose",
    )
    parser.add_argument(
        "--docker-command",
        default="docker",
        help="docker CLI executable used for Compose rendering",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        values = verify_static_contract(
            args.env_example, args.compose_file, args.secrets_compose_file
        )
        verify_application_property_contract(
            args.server_application_file,
            args.reference_consumer_application_file,
        )
        if not args.static_only:
            verify_docker_contract(
                values,
                args.compose_file,
                args.secrets_compose_file,
                docker_command=args.docker_command,
            )
    except ContractError as exc:
        print(f"compose environment contract: FAIL: {exc}", file=sys.stderr)
        return 1
    except Exception:
        # Avoid accidentally echoing a value from an unexpected parser/runtime error.
        print("compose environment contract: FAIL: unexpected verifier error", file=sys.stderr)
        return 2
    mode = "static" if args.static_only else "static+render"
    print(f"compose environment contract: PASS ({mode})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
