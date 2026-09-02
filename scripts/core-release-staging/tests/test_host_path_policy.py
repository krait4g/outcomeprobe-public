#!/usr/bin/env python3
"""Regression tests for the shared public-SBOM host-path policy."""

from __future__ import annotations

import hashlib
import pathlib
import sys
import unittest


SCRIPT_ROOT = pathlib.Path(__file__).parents[1]
sys.path.insert(0, str(SCRIPT_ROOT))

import host_path_policy  # noqa: E402


class HostPathPolicyTest(unittest.TestCase):
    def test_local_absolute_paths_are_rejected(self) -> None:
        blocked = (
            r"C:\Users\runner\work\artifact.jar",
            "D:/build/private/artifact.jar",
            r"\\buildhost\share\private\artifact.jar",
            "//buildhost/share/private/artifact.jar",
            "file:///C:/runner/private/artifact.jar",
            "file:///srv/private/artifact.jar",
            "file://buildhost/share/private/artifact.jar",
            "/home/runner/work/outcomeprobe/artifact.jar",
            "/github/workspace/private/artifact.jar",
            "/opt/hostedtoolcache/private/artifact.jar",
            "/tmp/private/artifact.jar",
            (
                "urn:syft:source@sha256:fixture/home/runner/work/_temp/"
                "candidate.oci.tar"
            ),
            r"urn:fixture:C:\Users\runner\work\artifact.jar",
            r"urn:fixture:C:\workspace-developer\private\artifact.jar",
        )
        for value in blocked:
            with self.subTest(value=value):
                self.assertTrue(host_path_policy.contains_local_absolute_path(value))

    def test_public_platform_and_package_identifiers_are_allowed(self) -> None:
        digest = "a" * 64
        allowed = (
            "linux/amd64",
            "linux/arm64",
            "example/outcomeprobe-public",
            "registry.example.org/outcomeprobe-public:0.1.0",
            f"registry.example.org/outcomeprobe-public@sha256:{digest}",
            "https://github.com/users/example/licenses/MIT",
            f"https://github.com/krait4g/outcomeprobe-public/sbom/0.1.0/container/"
            f"linux-amd64/{digest}",
            "pkg:maven/org.example/library@1.0.0",
            "pkg:deb/ubuntu/libc6@2.36?arch=amd64",
            "urn:uuid:12345678-1234-5678-1234-567812345678",
            f"urn:outcomeprobe:redacted-local-path:sha256:{digest}",
            f"sha256:{digest}",
        )
        for value in allowed:
            with self.subTest(value=value):
                self.assertFalse(host_path_policy.contains_local_absolute_path(value))

    def test_findings_expose_safe_location_and_digest_only(self) -> None:
        raw_value = "/home/runner/work/private/subject.json"
        raw_key = r"C:\Users\runner\private\key"
        document = {
            "components": [{"properties": [{"value": raw_value}]}],
            raw_key: {"nested": "safe"},
        }
        findings = host_path_policy.find_local_absolute_paths(document)
        rendered = host_path_policy.format_host_path_findings(findings)
        value_sha = hashlib.sha256(raw_value.encode("utf-8")).hexdigest()
        key_sha = hashlib.sha256(raw_key.encode("utf-8")).hexdigest()

        self.assertEqual(2, len(findings))
        self.assertIn("$.components[0].properties[0].value", rendered)
        self.assertIn(value_sha, rendered)
        self.assertIn(key_sha, rendered)
        self.assertIn(f"<key-sha256:{key_sha}>", rendered)
        self.assertNotIn(raw_value, rendered)
        self.assertNotIn(raw_key, rendered)


if __name__ == "__main__":
    unittest.main()
