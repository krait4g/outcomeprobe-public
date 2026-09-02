#!/usr/bin/env python3
"""Regression tests for the Docker-only canonical Artifact bootstrap."""

from __future__ import annotations

import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest


REPOSITORY = pathlib.Path(__file__).resolve().parents[3]
SCRIPT = REPOSITORY / "docker" / "bootstrap.sh"
DOCKERFILE = REPOSITORY / "docker" / "bootstrap.Dockerfile"
JAR_BUILD_DOCKERFILES = (
    REPOSITORY / "docker" / "server.Dockerfile",
    DOCKERFILE,
    REPOSITORY / "docker" / "realtime-kit.Dockerfile",
)
FIXTURE_NAMES = (
    "provider-contract.yaml",
    "target-profile-vulnerable.yaml",
    "target-profile-resilient.yaml",
    "scenario.yaml",
    "scenario-callback-duplicate.yaml",
)
PRODUCTION_CLI = "/opt/outcomeprobe/outcomeprobe-cli.jar"
PRODUCTION_FIXTURES = "/opt/outcomeprobe/golden-path/payment"
RAW_TOKEN = "bootstrap-test-control-token-0123456789abcdef"


def _find_shell() -> str | None:
    discovered = shutil.which("sh")
    if discovered:
        return discovered
    for candidate in (
        pathlib.Path("C:/Program Files/Git/bin/sh.exe"),
        pathlib.Path("C:/Program Files/Git/usr/bin/sh.exe"),
    ):
        if candidate.is_file():
            return str(candidate)
    return None


SHELL = _find_shell()


@unittest.skipUnless(SHELL, "POSIX sh is required")
class BootstrapScriptTest(unittest.TestCase):
    """Exercise the production script with a recording fake Java executable."""

    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="outcomeprobe-bootstrap-test-")
        self.root = pathlib.Path(self.temporary.name)
        self.cli = self.root / "outcomeprobe-cli.jar"
        self.fixtures = self.root / "fixtures"
        self.log = self.root / "java-calls.log"
        self.counter = self.root / "java-counter"
        self.bin = self.root / "bin"
        self.cli.write_bytes(b"fake-cli")
        self.fixtures.mkdir()
        for fixture_name in FIXTURE_NAMES:
            shutil.copyfile(
                REPOSITORY / "examples" / "golden-path" / "payment" / fixture_name,
                self.fixtures / fixture_name,
            )
        self.bin.mkdir()
        fake_java = self.bin / "java"
        fake_java.write_text(
            """#!/bin/sh
set -eu
count=0
if [ -f "$FAKE_JAVA_COUNTER" ]; then
  IFS= read -r count < "$FAKE_JAVA_COUNTER"
fi
count=$((count + 1))
printf '%s\\n' "$count" > "$FAKE_JAVA_COUNTER"
if [ -n "${OUTCOMEPROBE_CONTROL_API_TOKEN:-}" ]; then
  printf 'env-token=set' >> "$FAKE_JAVA_LOG"
else
  printf 'env-token=unset' >> "$FAKE_JAVA_LOG"
fi
for argument in "$@"; do
  printf '\\t%s' "$argument" >> "$FAKE_JAVA_LOG"
done
printf '\\n' >> "$FAKE_JAVA_LOG"
if [ "${FAKE_JAVA_FAIL_AT:-0}" -eq "$count" ]; then
  exit 17
fi
""",
            encoding="utf-8",
            newline="\n",
        )
        fake_java.chmod(0o755)
        production = SCRIPT.read_text(encoding="utf-8")
        test_script = (
            production.replace(PRODUCTION_CLI, self._shell_path(self.cli))
            .replace(PRODUCTION_FIXTURES, self._shell_path(self.fixtures))
        )
        self.script = self.root / "bootstrap.sh"
        self.script.write_text(test_script, encoding="utf-8", newline="\n")
        self.script.chmod(0o755)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    @staticmethod
    def _shell_path(path: pathlib.Path) -> str:
        value = path.resolve().as_posix()
        if len(value) >= 3 and value[1:3] == ":/":
            return "/" + value[0].lower() + value[2:]
        return value

    def _run(
        self, *, fail_at: int = 0, token: str | None = RAW_TOKEN
    ) -> subprocess.CompletedProcess[str]:
        environment = os.environ.copy()
        environment.update(
            {
                "PATH": str(self.bin) + os.pathsep + environment.get("PATH", ""),
                "FAKE_JAVA_LOG": str(self.log),
                "FAKE_JAVA_COUNTER": str(self.counter),
                "FAKE_JAVA_FAIL_AT": str(fail_at),
                "OUTCOMEPROBE_SERVER_URL": "http://outcomeprobe-server:8080",
            }
        )
        if token is None:
            environment.pop("OUTCOMEPROBE_CONTROL_API_TOKEN", None)
        else:
            environment["OUTCOMEPROBE_CONTROL_API_TOKEN"] = token
        return subprocess.run(
            [SHELL, str(self.script)],
            cwd=self.root,
            env=environment,
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
        )

    def _calls(self) -> list[list[str]]:
        if not self.log.exists():
            return []
        return [line.split("\t") for line in self.log.read_text(encoding="utf-8").splitlines()]

    def _expected_calls(self) -> list[list[str]]:
        common_tail = [
            "--server",
            "http://outcomeprobe-server:8080",
        ]
        calls: list[list[str]] = []
        for operation in ("validate", "import"):
            for fixture_name in FIXTURE_NAMES:
                calls.append(
                    [
                        "env-token=set",
                        "-jar",
                        self._shell_path(self.cli),
                        operation,
                        self._shell_path(self.fixtures / fixture_name),
                        *common_tail,
                    ]
                )
        return calls

    def test_validates_every_artifact_then_imports_in_dependency_order_twice(self) -> None:
        first = self._run()
        second = self._run()

        self.assertEqual(first.returncode, 0, first.stderr)
        self.assertEqual(second.returncode, 0, second.stderr)
        expected = self._expected_calls()
        self.assertEqual(self._calls(), expected + expected)
        combined = first.stdout + first.stderr + second.stdout + second.stderr
        self.assertNotIn(RAW_TOKEN, combined)
        self.assertNotIn(RAW_TOKEN, self.log.read_text(encoding="utf-8"))
        self.assertNotIn("Authorization", combined)

    def test_validation_failure_stops_before_every_import(self) -> None:
        result = self._run(fail_at=3)

        self.assertEqual(result.returncode, 17)
        calls = self._calls()
        self.assertEqual(calls, self._expected_calls()[:3])
        self.assertTrue(all(call[3] == "validate" for call in calls))
        self.assertNotIn("import", "\n".join(result.stdout.splitlines()))
        self.assertNotIn(RAW_TOKEN, result.stdout + result.stderr)

    def test_import_failure_stops_before_later_dependencies(self) -> None:
        result = self._run(fail_at=6)

        self.assertEqual(result.returncode, 17)
        self.assertEqual(self._calls(), self._expected_calls()[:6])
        self.assertNotIn(RAW_TOKEN, result.stdout + result.stderr)

    def test_missing_token_fails_before_java(self) -> None:
        result = self._run(token=None)

        self.assertEqual(result.returncode, 2)
        self.assertEqual(self._calls(), [])
        self.assertNotIn(RAW_TOKEN, result.stdout + result.stderr)
        self.assertIn("token", result.stderr)


class BootstrapDockerfilePolicyTest(unittest.TestCase):
    """Keep the bootstrap image narrow, non-root, and free of host helper tools."""

    def test_jar_build_images_copy_all_release_license_inputs(self) -> None:
        for dockerfile in JAR_BUILD_DOCKERFILES:
            with self.subTest(dockerfile=dockerfile.name):
                source = dockerfile.read_text(encoding="utf-8")
                self.assertEqual(source.count("COPY LICENSE ./"), 1)
                self.assertEqual(
                    source.count(
                        "COPY THIRD-PARTY-NOTICES THIRD-PARTY-LICENSES.json ./"
                    ),
                    1,
                )

    def test_runtime_is_non_root_and_copies_only_exact_canonical_artifacts(self) -> None:
        source = DOCKERFILE.read_text(encoding="utf-8")

        self.assertIn("USER 10001:10001", source)
        self.assertIn('ENTRYPOINT ["/opt/outcomeprobe/bootstrap.sh"]', source)
        self.assertNotIn("curl", source.lower())
        self.assertNotIn("jq", source.lower())
        for fixture_name in FIXTURE_NAMES:
            self.assertEqual(source.count(f"examples/golden-path/payment/{fixture_name}"), 1)
        self.assertNotIn("COPY examples/golden-path/payment/ ", source)

    def test_script_uses_token_environment_without_argv_forwarding(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")

        self.assertIn('OUTCOMEPROBE_CONTROL_API_TOKEN:-', source)
        self.assertNotIn("--token-file", source)
        self.assertNotIn("Bearer", source)
        self.assertNotIn("curl", source.lower())
        self.assertNotIn("jq", source.lower())
        self.assertNotIn("authorization", source.lower())


if __name__ == "__main__":
    unittest.main()
