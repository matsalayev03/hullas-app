"""Hullas device API — telefon agent uchun HTTP server.

Endpoints:
  GET  /health
  GET  /api/device/poll
  POST /api/device/result
  POST /api/device/live_frame
  POST /api/device/heartbeat
  GET  /api/device/download/<filename>
"""
from __future__ import annotations

import logging
import os
import secrets
from pathlib import Path

from flask import Flask, jsonify, request, send_from_directory

from device_store import DeviceStore

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("device_server")

app = Flask(__name__)

UPLOAD_DIR = Path(os.environ.get("UPLOAD_DIR", "/tmp/hullas_uploads"))
UPLOAD_DIR.mkdir(parents=True, exist_ok=True)

DB_PATH = Path(os.environ.get("DEVICE_DB_PATH", "/opt/hullas/device.db"))
DEVICE_TOKEN = os.environ.get("DEVICE_TOKEN", "")

store = DeviceStore(DB_PATH)


def _check_token() -> bool:
    if not DEVICE_TOKEN:
        return False
    token = request.headers.get("X-Device-Token", "")
    return secrets.compare_digest(token, DEVICE_TOKEN)


@app.route("/health", methods=["GET"])
def health():
    return jsonify({"status": "ok"}), 200


@app.route("/api/device/poll", methods=["GET"])
def poll():
    if not _check_token():
        return jsonify({"error": "unauthorized"}), 401

    cmd = store.poll_command()
    if cmd:
        log.info("Poll: %s -> %s", cmd["id"], cmd["cmd"])
    return jsonify({"command": cmd}), 200


@app.route("/api/device/heartbeat", methods=["POST"])
def heartbeat():
    if not _check_token():
        return jsonify({"error": "unauthorized"}), 401

    info = request.json.get("device") if request.is_json else None
    store.heartbeat(str(info) if info else None)
    return jsonify({"status": "ok"}), 200


@app.route("/api/device/result", methods=["POST"])
def result():
    if not _check_token():
        return jsonify({"error": "unauthorized"}), 401

    cmd_id = request.form.get("command_id", "")
    status = request.form.get("status", "failed")
    if not cmd_id:
        return jsonify({"error": "command_id required"}), 400

    result_data: dict = {}
    for key in ("lat", "lon", "error", "message"):
        val = request.form.get(key)
        if val:
            result_data[key] = val

    if "file" in request.files and request.files["file"].filename:
        f = request.files["file"]
        ext = Path(f.filename).suffix or ".bin"
        safe_name = secrets.token_hex(8) + ext
        path = UPLOAD_DIR / safe_name
        f.save(str(path))
        result_data["filename"] = safe_name
        result_data["url"] = f"/api/device/download/{safe_name}"
        log.info("Upload: %s -> %s (%s)", cmd_id, safe_name, status)

    if not store.complete_command(cmd_id, status, result_data):
        return jsonify({"error": "command not found"}), 404

    row = store.get_command(cmd_id)
    if row and row["cmd"] == "live_stop" and status == "done":
        session = store.stop_live_session()
        if session and session.get("command_id"):
            store.complete_command(
                session["command_id"],
                "done",
                {"frames": session.get("frame_count", 0)},
            )
            log.info(
                "Live stopped: %s frames",
                session.get("frame_count", 0),
            )
    elif row and row["cmd"] == "live_start" and status == "failed":
        store.stop_live_session()
        log.info("Live failed: %s", result_data.get("error"))

    return jsonify({"status": "ok"}), 200


@app.route("/api/device/live_frame", methods=["POST"])
def live_frame():
    if not _check_token():
        return jsonify({"error": "unauthorized"}), 401

    cmd_id = request.form.get("command_id", "")
    session = store.get_live_session()
    if not session or session["command_id"] != cmd_id:
        return jsonify({"error": "no active live session"}), 400

    if "file" not in request.files or not request.files["file"].filename:
        return jsonify({"error": "file required"}), 400

    f = request.files["file"]
    ext = Path(f.filename).suffix or ".png"
    safe_name = secrets.token_hex(8) + ext
    path = UPLOAD_DIR / safe_name
    f.save(str(path))
    frame_id = store.add_live_frame(safe_name)
    log.info("Live frame: %s -> %s", cmd_id, safe_name)
    return jsonify({"status": "ok", "frame_id": frame_id}), 200


@app.route("/api/device/download/<filename>", methods=["GET"])
def download(filename: str):
    if ".." in filename or "/" in filename or "\\" in filename:
        return jsonify({"error": "invalid"}), 400
    path = UPLOAD_DIR / filename
    if not path.exists():
        return jsonify({"error": "not found"}), 404
    return send_from_directory(str(UPLOAD_DIR), filename, as_attachment=True)


if __name__ == "__main__":
    host = os.environ.get("DEVICE_HOST", "127.0.0.1")
    port = int(os.environ.get("DEVICE_PORT", "5555"))
    log.info("Device server: http://%s:%d", host, port)
    app.run(host=host, port=port, debug=False)