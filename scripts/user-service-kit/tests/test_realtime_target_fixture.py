from __future__ import annotations

import hashlib
import hmac
import http.client
import importlib.util
import json
import pathlib
import re
import threading
import unittest


REPOSITORY = pathlib.Path(__file__).parents[3]
TARGET_PATH = REPOSITORY / "examples/realtime-track/target-fixture/realtime_track_target.py"
SPEC = importlib.util.spec_from_file_location("realtime_track_target", TARGET_PATH)
assert SPEC is not None and SPEC.loader is not None
target = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(target)


class RealtimeTargetFixtureTest(unittest.TestCase):
    def setUp(self) -> None:
        self.token = "target-token-0000000000000000000000000000"
        self.callback_secret = "callback-secret-0000000000000000000000000"
        self.server = target.RealtimeTargetServer(
            "127.0.0.1", 0, self.token, self.callback_secret
        )
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def request(
        self,
        method: str,
        path: str,
        body: dict[str, object] | None = None,
        authorization: str | None = None,
        signature: str | None = None,
    ) -> tuple[int, dict[str, object] | None]:
        raw = b"" if body is None else json.dumps(body, separators=(",", ":")).encode()
        headers = {"Content-Length": str(len(raw))}
        if authorization is not None:
            headers["Authorization"] = authorization
        if signature is not None:
            headers["X-OutcomeProbe-Signature"] = signature
        connection = http.client.HTTPConnection(
            "127.0.0.1", self.server.server_port, timeout=2
        )
        connection.request(method, path, body=raw, headers=headers)
        response = connection.getresponse()
        response_raw = response.read()
        status = response.status
        connection.close()
        return status, json.loads(response_raw) if response_raw else None

    def test_missing_wrong_and_valid_target_auth(self) -> None:
        path = "/test-support/outcomeprobe/radar-state/auth-check"
        self.assertEqual(401, self.request("GET", path)[0])
        self.assertEqual(401, self.request("GET", path, authorization="Bearer wrong")[0])
        status, value = self.request(
            "GET", path, authorization="Bearer " + self.token
        )
        self.assertEqual(200, status)
        self.assertEqual(0, value["localMappingCount"])

    def test_forged_hmac_is_rejected_and_valid_hmac_is_applied(self) -> None:
        payload = {
            "eventId": "evt-1",
            "type": "TRACK_FUSED",
            "data": {
                "trackKey": "track-1",
                "version": 1,
                "latitude": 37.5,
                "longitude": 127.0,
                "eventTsEpochMs": 1785000000000,
            },
        }
        auth = "Bearer " + self.token
        self.assertEqual(
            401,
            self.request(
                "POST",
                "/webhooks/track-fused",
                payload,
                authorization=auth,
                signature="0" * 64,
            )[0],
        )
        _, rejected = self.request(
            "GET",
            "/test-support/outcomeprobe/radar-state/track-1",
            authorization=auth,
        )
        self.assertEqual(0, rejected["processedEventCount"])
        self.assertEqual(1, rejected["rejectedCallbackCount"])

        raw = json.dumps(payload, separators=(",", ":")).encode()
        signature = hmac.new(
            self.callback_secret.encode(), raw, hashlib.sha256
        ).hexdigest()
        self.assertEqual(
            200,
            self.request(
                "POST",
                "/webhooks/track-fused",
                payload,
                authorization=auth,
                signature=signature,
            )[0],
        )
        _, accepted = self.request(
            "GET",
            "/test-support/outcomeprobe/radar-state/track-1",
            authorization=auth,
        )
        self.assertEqual(1, accepted["processedEventCount"])
        self.assertEqual(1, accepted["appliedEventCount"])
        self.assertEqual(1, accepted["members"][0]["version"])

    def test_duplicate_and_stale_events_do_not_reapply_state(self) -> None:
        store = target.TrackStore()
        newer = {
            "eventId": "newer",
            "trackKey": "track-2",
            "version": 2,
            "latitude": 37.521,
            "longitude": 127.021,
            "eventTsEpochMs": 2,
        }
        older = {
            "eventId": "older",
            "trackKey": "track-2",
            "version": 1,
            "latitude": 37.499,
            "longitude": 126.999,
            "eventTsEpochMs": 1,
        }
        self.assertEqual("APPLIED", store.accept(newer))
        self.assertEqual("DUPLICATE", store.accept(newer))
        self.assertEqual("STALE", store.accept(older))
        observation = store.observation("track-2")
        self.assertEqual(3, observation["receivedEventCount"])
        self.assertEqual(2, observation["processedEventCount"])
        self.assertEqual(1, observation["appliedEventCount"])
        self.assertEqual(2, observation["members"][0]["version"])


class RealtimeKitStaticContractTest(unittest.TestCase):
    def test_overlay_has_exact_internal_target_boundary(self) -> None:
        overlay = (REPOSITORY / "compose.realtime-track.yaml").read_text(encoding="utf-8")
        self.assertIn("context: ./examples/realtime-track/target-fixture", overlay)
        self.assertIn("- realtime-track-target", overlay)
        self.assertNotRegex(overlay, re.compile(r"^\s+ports:\s*$", re.MULTILINE))
        self.assertGreaterEqual(overlay.count("read_only: true"), 2)
        self.assertGreaterEqual(overlay.count("no-new-privileges:true"), 2)
        self.assertGreaterEqual(overlay.count("pids_limit: 128"), 2)
        self.assertIn("- realtime-kit-evidence:/evidence", overlay)
        self.assertRegex(overlay, re.compile(r"(?m)^volumes:\n  realtime-kit-evidence:\s*$"))

    def test_public_artifact_inventory_and_secret_refs_are_exact(self) -> None:
        root = REPOSITORY / "examples/realtime-track"
        expected = {
            "README.md",
            "provider-contract.yaml",
            "target-profile.compose.yaml",
            "scenario-single-event.yaml",
            "scenario-duplicate-3x.yaml",
            "scenario-out-of-order.yaml",
        }
        self.assertEqual(expected, {path.name for path in root.iterdir() if path.is_file()})
        profile = (root / "target-profile.compose.yaml").read_text(encoding="utf-8")
        contract = (root / "provider-contract.yaml").read_text(encoding="utf-8")
        self.assertIn("baseUri: http://realtime-track-target:8181", profile)
        self.assertIn("secretRef: realtime-target-token", profile)
        self.assertIn("secretRef: realtime-callback-signing-key", contract)
        self.assertNotIn("localhost", profile)
        for scenario in root.glob("scenario-*.yaml"):
            value = scenario.read_text(encoding="utf-8")
            self.assertRegex(value, r"(?m)^  version: 1\.0\.0$")
            self.assertIn("targetProfileRef: realtime-track-compose", value)

    def test_runner_avoids_indirect_eval_and_raw_evidence_publication(self) -> None:
        runner = (REPOSITORY / "docker/realtime-kit.sh").read_text(encoding="utf-8")
        runner_image = (REPOSITORY / "docker/realtime-kit.Dockerfile").read_text(
            encoding="utf-8"
        )
        verifier = (
            REPOSITORY / "scripts/user-service-kit/verify-user-service-kit.sh"
        ).read_text(encoding="utf-8")
        dockerignore = (REPOSITORY / ".dockerignore").read_text(encoding="utf-8")
        self.assertNotRegex(runner, r"\beval\b")
        self.assertIn("chown outcomeprobe:outcomeprobe /evidence", runner_image)
        self.assertIn("chmod 0700 /evidence", runner_image)
        self.assertIn("USER 10001:10001", runner_image)
        self.assertIn("--assets-output /evidence/runs", runner)
        self.assertIn("current_stage=normalize-collect", runner)
        self.assertIn("current_stage=normalize-permissions", runner)
        self.assertIn("failed at stage: %s", runner)
        self.assertIn("mv -T -n", verifier)
        self.assertIn("safe_diagnostic=$(grep -E", verifier)
        self.assertIn("safe_evidence_detail=$(grep -E", verifier)
        self.assertIn("safe_python_error=$(grep -E", verifier)
        self.assertIn('runner_evidence_mount=$(isolated docker inspect', verifier)
        self.assertIn('docker cp "$runner_name:/evidence/." "$evidence_dir"', verifier)
        self.assertIn(
            'runtime_root=$(mktemp -d "${TMPDIR:-/tmp}/outcomeprobe-user-service-kit.runtime.XXXXXX")',
            verifier,
        )
        self.assertIn('"$repository/"*)', verifier)
        self.assertIn("temporary credential root must be outside the repository", verifier)
        self.assertIn("env_file", verifier)
        self.assertIn("isolated docker compose --env-file", verifier)
        self.assertIn("wrong_auth_token='synthetic-'", verifier)
        self.assertIn("wrong_auth_token+='wrong-auth-'", verifier)
        self.assertIn("wrong_auth_token+='fixture-value'", verifier)
        self.assertIn("for required_ignore in", verifier)
        for pattern in (
            ".user-service-kit.runtime.*/",
            "**/.user-service-kit.runtime.*/",
            ".user-service-kit.staging.*/",
            "**/.user-service-kit.staging.*/",
        ):
            self.assertIn(pattern, dockerignore.splitlines())


if __name__ == "__main__":
    unittest.main()
