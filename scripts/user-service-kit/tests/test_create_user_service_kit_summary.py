from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import os
import pathlib
import tempfile
import unittest
import uuid
import zipfile
from unittest import mock


HELPER_PATH = pathlib.Path(__file__).parents[1] / "create_user_service_kit_summary.py"
SPEC = importlib.util.spec_from_file_location("user_service_kit_summary", HELPER_PATH)
assert SPEC is not None and SPEC.loader is not None
helper = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(helper)

SOURCE_SHA = "1" * 40


class PublishedEvidenceMutationTest(unittest.TestCase):
    def valid_tree(self, parent: pathlib.Path) -> tuple[pathlib.Path, dict[str, object]]:
        root = parent / "evidence"
        root.mkdir()
        records: list[dict[str, object]] = []
        for index, (key, name, seed, callback_count) in enumerate(helper.SCENARIOS, start=1):
            run_id = str(uuid.UUID(int=index))
            report, junit, bundle = helper.normalized_assets(
                key,
                name,
                seed,
                callback_count,
                run_id,
                2,
                str(index) * 64,
            )
            relative_report, relative_junit, relative_bundle = helper.ASSET_NAMES[key]
            for relative, raw in (
                (relative_report, report),
                (relative_junit, junit),
                (relative_bundle, bundle),
            ):
                target = root / pathlib.Path(*relative.parts)
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(raw)
            records.append(
                {
                    "name": name,
                    "version": "1.0.0",
                    "status": "PASSED",
                    "runId": run_id,
                    "seed": seed,
                    "callbackCount": callback_count,
                    "probeAttemptCount": 2,
                    "business": helper.BUSINESS[key],
                    "reportSha256": hashlib.sha256(report).hexdigest(),
                    "junitSha256": hashlib.sha256(junit).hexdigest(),
                    "bundleSha256": hashlib.sha256(bundle).hexdigest(),
                }
            )

        summary: dict[str, object] = {
            "schemaVersion": 1,
            "result": "PASS",
            "source": {"kind": "working-tree", "shaBinding": "argument"},
            "sourceSha": SOURCE_SHA,
            "artifacts": {"providerContract": 1, "targetProfile": 1, "scenario": 3},
            "topology": {
                "targetProfile": "realtime-track-compose",
                "targetService": "realtime-track-target",
                "targetHost": "realtime-track-target",
                "targetPort": 8181,
                "hostPublished": False,
                "network": "app",
            },
            "connection": {"readiness": "PASS", "doctor": "PASS"},
            "security": {
                "targetTokenSecretRef": "realtime-target-token",
                "callbackSecretRef": "realtime-callback-signing-key",
                "validHmac": "PASS",
                "forgedHmacRejected": "PASS",
                "invalidTargetAuthRejected": True,
                "rawSecretFindings": 0,
            },
            "scenarios": records,
            "execution": {"runCount": 3, "uniqueRunIds": 3},
            "cleanup": {
                "containers": 0,
                "networks": 0,
                "volumes": 0,
                "temporaryCredentialsRemoved": True,
            },
            "pushPerformed": False,
        }
        (root / "user-service-kit-summary.json").write_text(
            json.dumps(summary, sort_keys=True, separators=(",", ":")) + "\n",
            encoding="utf-8",
        )
        return root, summary

    def assert_rejected(self, summary: dict[str, object], root: pathlib.Path) -> None:
        with self.assertRaises(helper.EvidenceError):
            helper.validate_summary(summary, SOURCE_SHA, root)

    def test_valid_exact_ten_file_tree(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, summary = self.valid_tree(pathlib.Path(directory))
            helper.validate_summary(summary, SOURCE_SHA, root)
            with zipfile.ZipFile(root / "runs/single/bundle.zip") as archive:
                self.assertEqual(
                    [zipfile.ZIP_STORED, zipfile.ZIP_STORED, zipfile.ZIP_STORED],
                    [entry.compress_type for entry in archive.infolist()],
                )
                self.assertEqual([3, 3, 3], [entry.create_system for entry in archive.infolist()])

    def test_wrong_source_and_digest_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, summary = self.valid_tree(pathlib.Path(directory))
            with self.assertRaises(helper.EvidenceError):
                helper.validate_summary(summary, "2" * 40, root)
            mutated = copy.deepcopy(summary)
            mutated["scenarios"][0]["reportSha256"] = "0" * 64
            self.assert_rejected(mutated, root)

    def test_extra_and_missing_assets_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, summary = self.valid_tree(pathlib.Path(directory))
            (root / "extra.txt").write_text("extra", encoding="utf-8")
            self.assert_rejected(summary, root)
        with tempfile.TemporaryDirectory() as directory:
            root, summary = self.valid_tree(pathlib.Path(directory))
            (root / "runs/single/report.json").unlink()
            self.assert_rejected(summary, root)

    def test_symlink_asset_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, summary = self.valid_tree(pathlib.Path(directory))
            report = root / "runs/single/report.json"
            original = pathlib.Path.is_symlink
            with mock.patch.object(
                pathlib.Path,
                "is_symlink",
                lambda path: path == report or original(path),
            ):
                self.assert_rejected(summary, root)

    def test_unsafe_zip_path_is_rejected_even_with_matching_digest(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, summary = self.valid_tree(pathlib.Path(directory))
            bundle = root / "runs/single/bundle.zip"
            with zipfile.ZipFile(bundle, "w") as archive:
                archive.writestr("../escape", b"no")
            summary["scenarios"][0]["bundleSha256"] = hashlib.sha256(
                bundle.read_bytes()
            ).hexdigest()
            self.assert_rejected(summary, root)

    def test_secret_sentinel_is_rejected(self) -> None:
        sentinel = "sentinel-secret-value-00000000000000000000"
        old = os.environ.get("OUTCOMEPROBE_CONTROL_API_TOKEN")
        os.environ["OUTCOMEPROBE_CONTROL_API_TOKEN"] = sentinel
        try:
            with tempfile.TemporaryDirectory() as directory:
                root, summary = self.valid_tree(pathlib.Path(directory))
                report = root / "runs/single/report.json"
                value = json.loads(report.read_text(encoding="utf-8"))
                value["business"] = sentinel
                report.write_text(
                    json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n",
                    encoding="utf-8",
                )
                summary["scenarios"][0]["reportSha256"] = hashlib.sha256(
                    report.read_bytes()
                ).hexdigest()
                with self.assertRaisesRegex(helper.EvidenceError, "raw secret"):
                    helper.validate_summary(summary, SOURCE_SHA, root)
        finally:
            if old is None:
                os.environ.pop("OUTCOMEPROBE_CONTROL_API_TOKEN", None)
            else:
                os.environ["OUTCOMEPROBE_CONTROL_API_TOKEN"] = old

    def test_duplicate_run_id_and_callback_count_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, summary = self.valid_tree(pathlib.Path(directory))
            duplicated = copy.deepcopy(summary)
            duplicated["scenarios"][1]["runId"] = duplicated["scenarios"][0]["runId"]
            self.assert_rejected(duplicated, root)
            wrong_count = copy.deepcopy(summary)
            wrong_count["scenarios"][1]["callbackCount"] = 2
            self.assert_rejected(wrong_count, root)

    def test_unknown_summary_and_scenario_fields_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, summary = self.valid_tree(pathlib.Path(directory))
            unknown_summary = copy.deepcopy(summary)
            unknown_summary["extra"] = "sentinel-secret-value-00000000000000000000"
            self.assert_rejected(unknown_summary, root)

            unknown_scenario = copy.deepcopy(summary)
            unknown_scenario["scenarios"][0]["extra"] = (
                "sentinel-secret-value-00000000000000000000"
            )
            self.assert_rejected(unknown_scenario, root)

    def test_noncanonical_and_duplicate_key_json_are_rejected(self) -> None:
        sentinel = "sentinel-secret-value-00000000000000000000"
        with tempfile.TemporaryDirectory() as directory:
            root, summary = self.valid_tree(pathlib.Path(directory))
            summary_path = root / "user-service-kit-summary.json"
            canonical = summary_path.read_text(encoding="utf-8")
            summary_path.write_text(
                '{"result":"' + sentinel + '",' + canonical[1:],
                encoding="utf-8",
            )
            with self.assertRaisesRegex(helper.EvidenceError, "not canonical"):
                helper.verify_summary(summary_path, SOURCE_SHA)

            receipt = copy.deepcopy(summary)
            for field in ("sourceSha", "cleanup", "pushPerformed"):
                receipt.pop(field)
            receipt_path = root / "receipt.json"
            receipt_path.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(helper.EvidenceError, "not canonical"):
                helper.finalize(receipt_path, SOURCE_SHA, root / "new-summary.json")


if __name__ == "__main__":
    unittest.main()
