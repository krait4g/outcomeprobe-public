#!/usr/bin/env python3
"""Self-contained black-box Target used only by the opt-in realtime kit."""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote, urlsplit

MAX_BODY_BYTES = 64 * 1024
SAFE_TRACK_KEY = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$")


class TrackStore:
    """Thread-safe target state with duplicate and stale-event protection."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._tracks: dict[str, dict[str, object]] = {}
        self._processed: dict[str, set[str]] = {}
        self._received: dict[str, int] = {}
        self._applied: dict[str, int] = {}
        self._rejected_callbacks: dict[str, int] = {}

    def reset(self) -> None:
        with self._lock:
            self._tracks.clear()
            self._processed.clear()
            self._received.clear()
            self._applied.clear()
            self._rejected_callbacks.clear()

    def reject_callback(self, track_key: str) -> None:
        if not SAFE_TRACK_KEY.fullmatch(track_key):
            return
        with self._lock:
            self._rejected_callbacks[track_key] = self._rejected_callbacks.get(track_key, 0) + 1

    def accept(self, event: dict[str, object]) -> str:
        track_key = required_text(event, "trackKey")
        event_id = required_text(event, "eventId")
        version = required_int(event, "version")
        timestamp = required_int(event, "eventTsEpochMs")
        latitude = required_number(event, "latitude")
        longitude = required_number(event, "longitude")
        if not SAFE_TRACK_KEY.fullmatch(track_key):
            raise ValueError("invalid trackKey")
        if version < 1 or timestamp < 0:
            raise ValueError("invalid event ordering fields")

        with self._lock:
            self._received[track_key] = self._received.get(track_key, 0) + 1
            processed = self._processed.setdefault(track_key, set())
            if event_id in processed:
                return "DUPLICATE"
            processed.add(event_id)

            current = self._tracks.get(track_key)
            if current is not None:
                current_order = (int(current["eventTsEpochMs"]), int(current["version"]))
                if (timestamp, version) <= current_order:
                    return "STALE"

            self._tracks[track_key] = {
                "trackKey": track_key,
                "globalId": "global-" + track_key,
                "representativeId": "global-" + track_key,
                "latitude": latitude,
                "longitude": longitude,
                "eventTsEpochMs": timestamp,
                "version": version,
            }
            self._applied[track_key] = self._applied.get(track_key, 0) + 1
            return "APPLIED"

    def observation(self, track_key: str) -> dict[str, object]:
        if not SAFE_TRACK_KEY.fullmatch(track_key):
            raise ValueError("invalid trackKey")
        with self._lock:
            track = self._tracks.get(track_key)
            members = [] if track is None else [dict(track)]
            return {
                "localMappingCount": len(members),
                "distinctGlobalIdCount": len({member["globalId"] for member in members}),
                "distinctRepresentativeIdCount": len(
                    {member["representativeId"] for member in members}
                ),
                "receivedEventCount": self._received.get(track_key, 0),
                "processedEventCount": len(self._processed.get(track_key, set())),
                "appliedEventCount": self._applied.get(track_key, 0),
                "rejectedCallbackCount": self._rejected_callbacks.get(track_key, 0),
                "members": members,
            }


def required_text(value: dict[str, object], key: str) -> str:
    candidate = value.get(key)
    if not isinstance(candidate, str) or not candidate:
        raise ValueError(f"invalid {key}")
    return candidate


def required_int(value: dict[str, object], key: str) -> int:
    candidate = value.get(key)
    if isinstance(candidate, bool) or not isinstance(candidate, int):
        raise ValueError(f"invalid {key}")
    return candidate


def required_number(value: dict[str, object], key: str) -> int | float:
    candidate = value.get(key)
    if isinstance(candidate, bool) or not isinstance(candidate, (int, float)):
        raise ValueError(f"invalid {key}")
    return candidate


def callback_event(payload: dict[str, object]) -> dict[str, object]:
    data = payload.get("data")
    if not isinstance(data, dict):
        raise ValueError("invalid callback data")
    return {
        "eventId": required_text(payload, "eventId"),
        "trackKey": required_text(data, "trackKey"),
        "version": required_int(data, "version"),
        "latitude": required_number(data, "latitude"),
        "longitude": required_number(data, "longitude"),
        "eventTsEpochMs": required_int(data, "eventTsEpochMs"),
    }


class TargetHandler(BaseHTTPRequestHandler):
    """Bounded HTTP interface; no request body, token, or signature is logged."""

    server_version = "OutcomeProbeRealtimeTarget/1"
    protocol_version = "HTTP/1.1"

    @property
    def store(self) -> TrackStore:
        return self.server.store  # type: ignore[attr-defined]

    def log_message(self, _format: str, *_args: object) -> None:
        return

    def do_GET(self) -> None:  # noqa: N802 - stdlib callback name
        path = urlsplit(self.path).path
        if path == "/health":
            self._json(200, {"status": "UP"})
            return
        prefix = "/test-support/outcomeprobe/radar-state/"
        authorized = self._authorized()
        if not path.startswith(prefix) or not authorized:
            self._empty(401 if not authorized else 404)
            return
        track_key = unquote(path[len(prefix) :])
        try:
            self._json(200, self.store.observation(track_key))
        except ValueError:
            self._json(400, {"error": "invalid track key"})

    def do_POST(self) -> None:  # noqa: N802 - stdlib callback name
        path = urlsplit(self.path).path
        if not self._authorized():
            self._empty(401)
            return
        try:
            raw = self._read_body()
        except ValueError:
            self.close_connection = True
            self._json(413, {"error": "invalid request body"})
            return

        if path == "/test-support/outcomeprobe/reset":
            self.store.reset()
            self._empty(204)
            return
        if path == "/api/v1/events":
            self._accept_direct(raw)
            return
        if path == "/webhooks/track-fused":
            self._accept_callback(raw)
            return
        self._empty(404)

    def _accept_direct(self, raw: bytes) -> None:
        try:
            event = self._object(raw)
            outcome = self.store.accept(event)
            self._json(200, {"res_code": 200, "outcome": outcome})
        except ValueError:
            self._json(400, {"res_code": 400, "error": "invalid event"})

    def _accept_callback(self, raw: bytes) -> None:
        signature = self.headers.get("X-OutcomeProbe-Signature", "")
        expected = hmac.new(self.server.callback_secret, raw, hashlib.sha256).hexdigest()  # type: ignore[attr-defined]
        if not hmac.compare_digest(expected, signature):
            try:
                payload = self._object(raw)
                data = payload.get("data")
                if isinstance(data, dict) and isinstance(data.get("trackKey"), str):
                    self.store.reject_callback(str(data["trackKey"]))
            except ValueError:
                pass
            self._empty(401)
            return
        try:
            outcome = self.store.accept(callback_event(self._object(raw)))
            self._json(200, {"accepted": True, "outcome": outcome})
        except ValueError:
            self._json(400, {"error": "invalid callback"})

    def _authorized(self) -> bool:
        return hmac.compare_digest(
            self.headers.get("Authorization", ""), self.server.authorization  # type: ignore[attr-defined]
        )

    def _read_body(self) -> bytes:
        if self.headers.get("Transfer-Encoding"):
            raise ValueError("transfer encoding is unsupported")
        raw_length = self.headers.get("Content-Length")
        if raw_length is None or not raw_length.isdigit():
            raise ValueError("content length is required")
        length = int(raw_length)
        if length < 0 or length > MAX_BODY_BYTES:
            raise ValueError("body too large")
        return self.rfile.read(length)

    @staticmethod
    def _object(raw: bytes) -> dict[str, object]:
        try:
            value = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ValueError("invalid JSON") from error
        if not isinstance(value, dict):
            raise ValueError("JSON object required")
        return value

    def _json(self, status: int, value: dict[str, object]) -> None:
        body = json.dumps(value, ensure_ascii=True, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _empty(self, status: int) -> None:
        self.send_response(status)
        self.send_header("Content-Length", "0")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()


class RealtimeTargetServer(ThreadingHTTPServer):
    """HTTP server carrying only immutable credentials and the in-memory store."""

    daemon_threads = True

    def __init__(self, host: str, port: int, token: str, callback_secret: str) -> None:
        super().__init__((host, port), TargetHandler)
        self.authorization = "Bearer " + token
        self.callback_secret = callback_secret.encode("utf-8")
        self.store = TrackStore()


def main() -> None:
    token = os.environ.get("OUTCOMEPROBE_REALTIME_TARGET_TOKEN", "")
    callback_secret = os.environ.get("OUTCOMEPROBE_REALTIME_CALLBACK_SECRET", "")
    if len(token) < 32 or len(callback_secret) < 32 or token == callback_secret:
        raise SystemExit("realtime Target credentials must be distinct and at least 32 characters")
    port = int(os.environ.get("OUTCOMEPROBE_REALTIME_TARGET_PORT", "8181"))
    server = RealtimeTargetServer("0.0.0.0", port, token, callback_secret)
    try:
        server.serve_forever(poll_interval=0.2)
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
