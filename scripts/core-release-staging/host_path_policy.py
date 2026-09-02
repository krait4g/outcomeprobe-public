#!/usr/bin/env python3
"""Classify local absolute paths without exposing their original values."""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass
from typing import Any, Iterator


LOCAL_ABSOLUTE_PATH_PATTERNS = (
    re.compile(r"(?i)(?:^|[\s\"'=(,;:\[<{])(?:file:/+)?[a-z]:[\\/]"),
    re.compile(
        r"(?i)(?:^|[\s\"'=(,;\[<{])(?:\\\\[^\\/\s]+[\\/][^\\/\s]+|"
        r"//[^/:\s]+/[^/\s]+)"
    ),
    re.compile(r"(?i)(?:^|[\s\"'=(,;:\[<{])file:(?:/{1,3}|\\\\)"),
    re.compile(r"(?:^|[\s\"'=(,;:\[<{])/(?!/)"),
)
RUNNER_EMBEDDED_PATH_PATTERNS = (
    re.compile(
        r"(?i)[a-z]:[\\/](?:users|documents|workspaces?(?:-[a-z0-9._-]+)?)[\\/]"
    ),
    re.compile(
        r"(?i)/(?:home/runner/work|github/workspace|opt/hostedtoolcache|"
        r"release-staging)/"
    ),
    re.compile(r"(?i)[\\/]_temp[\\/]"),
)
SAFE_JSON_KEY = re.compile(r"^[A-Za-z_][A-Za-z0-9_.:-]*$")


@dataclass(frozen=True)
class HostPathFinding:
    """A non-reversible host-path finding tied to a safe JSON location."""

    location: str
    value_sha256: str


def sha256_text(value: str) -> str:
    """Return the lowercase SHA-256 of one JSON string."""
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def contains_local_absolute_path(value: str) -> bool:
    """Return true for Windows, UNC, file-URI, or POSIX absolute paths."""
    return any(
        pattern.search(value)
        for pattern in (
            *LOCAL_ABSOLUTE_PATH_PATTERNS,
            *RUNNER_EMBEDDED_PATH_PATTERNS,
        )
    )


def safe_key_segment(key: str) -> str:
    """Keep schema-like keys readable and hash every unsafe key segment."""
    if SAFE_JSON_KEY.fullmatch(key):
        return key
    return f"<key-sha256:{sha256_text(key)}>"


def iter_json_strings(
    value: Any, location: str = "$"
) -> Iterator[tuple[str, str]]:
    """Yield every JSON key and value with a location that cannot leak raw keys."""
    if isinstance(value, dict):
        for key, child in value.items():
            key_text = str(key)
            child_location = f"{location}.{safe_key_segment(key_text)}"
            yield f"{child_location}.<key>", key_text
            yield from iter_json_strings(child, child_location)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from iter_json_strings(child, f"{location}[{index}]")
    elif isinstance(value, str):
        yield location, value


def find_local_absolute_paths(
    value: Any, location: str = "$"
) -> list[HostPathFinding]:
    """Return safe-location and value-digest findings in traversal order."""
    return [
        HostPathFinding(location=row_location, value_sha256=sha256_text(row_value))
        for row_location, row_value in iter_json_strings(value, location)
        if contains_local_absolute_path(row_value)
    ]


def format_host_path_findings(
    findings: list[HostPathFinding], limit: int = 10
) -> str:
    """Format bounded findings without reproducing the original path values."""
    return "; ".join(
        f"location={finding.location} valueSha256={finding.value_sha256}"
        for finding in findings[:limit]
    )
