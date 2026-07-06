"""Device command queue — SQLite storage for Hullas remote agent."""
from __future__ import annotations

import json
import sqlite3
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


class DeviceStore:
    def __init__(self, path: Path) -> None:
        self._path = path
        self._init()

    def _conn(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self._path)
        conn.row_factory = sqlite3.Row
        return conn

    def _init(self) -> None:
        with self._conn() as db:
            db.executescript(
                """
                CREATE TABLE IF NOT EXISTS device_commands (
                    id          TEXT PRIMARY KEY,
                    cmd         TEXT NOT NULL,
                    args        TEXT NOT NULL DEFAULT '{}',
                    status      TEXT NOT NULL DEFAULT 'pending',
                    result      TEXT,
                    created_at  TEXT NOT NULL,
                    completed_at TEXT
                );
                CREATE INDEX IF NOT EXISTS idx_cmd_status
                    ON device_commands(status, created_at);

                CREATE TABLE IF NOT EXISTS device_heartbeat (
                    id          INTEGER PRIMARY KEY CHECK(id = 1),
                    last_seen   TEXT,
                    device_info TEXT
                );

                CREATE TABLE IF NOT EXISTS live_session (
                    id          INTEGER PRIMARY KEY CHECK(id = 1),
                    active      INTEGER NOT NULL DEFAULT 0,
                    command_id  TEXT,
                    started_at  TEXT,
                    frame_count INTEGER NOT NULL DEFAULT 0
                );

                CREATE TABLE IF NOT EXISTS live_frames (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    filename    TEXT NOT NULL,
                    created_at  TEXT NOT NULL,
                    sent        INTEGER NOT NULL DEFAULT 0
                );
                CREATE INDEX IF NOT EXISTS idx_live_frames_sent
                    ON live_frames(sent, created_at);
                """
            )

    def create_command(self, cmd: str, args: dict | None = None) -> str:
        cmd_id = uuid.uuid4().hex[:16]
        with self._conn() as db:
            db.execute(
                "INSERT INTO device_commands (id, cmd, args, created_at) VALUES (?, ?, ?, ?)",
                (cmd_id, cmd, json.dumps(args or {}), _now()),
            )
        return cmd_id

    def poll_command(self) -> dict | None:
        with self._conn() as db:
            row = db.execute(
                """
                SELECT id, cmd, args FROM device_commands
                WHERE status = 'pending'
                ORDER BY created_at ASC LIMIT 1
                """
            ).fetchone()
            if not row:
                return None
            db.execute(
                "UPDATE device_commands SET status = 'running' WHERE id = ?",
                (row["id"],),
            )
            return {
                "id": row["id"],
                "cmd": row["cmd"],
                "args": json.loads(row["args"]),
            }

    def complete_command(
        self,
        cmd_id: str,
        status: str,
        result: dict[str, Any] | None = None,
    ) -> bool:
        with self._conn() as db:
            cur = db.execute(
                """
                UPDATE device_commands
                SET status = ?, result = ?, completed_at = ?
                WHERE id = ? AND status IN ('pending', 'running')
                """,
                (status, json.dumps(result or {}), _now(), cmd_id),
            )
            return cur.rowcount > 0

    def get_command(self, cmd_id: str) -> dict | None:
        with self._conn() as db:
            row = db.execute(
                "SELECT * FROM device_commands WHERE id = ?", (cmd_id,)
            ).fetchone()
            if not row:
                return None
            return {
                "id": row["id"],
                "cmd": row["cmd"],
                "args": json.loads(row["args"]),
                "status": row["status"],
                "result": json.loads(row["result"]) if row["result"] else None,
                "created_at": row["created_at"],
                "completed_at": row["completed_at"],
            }

    def heartbeat(self, device_info: str | None = None) -> None:
        with self._conn() as db:
            row = db.execute("SELECT id FROM device_heartbeat WHERE id = 1").fetchone()
            if row:
                db.execute(
                    "UPDATE device_heartbeat SET last_seen = ?, device_info = ? WHERE id = 1",
                    (_now(), device_info),
                )
            else:
                db.execute(
                    "INSERT INTO device_heartbeat (id, last_seen, device_info) VALUES (1, ?, ?)",
                    (_now(), device_info),
                )

    def last_seen(self) -> dict | None:
        with self._conn() as db:
            row = db.execute("SELECT * FROM device_heartbeat WHERE id = 1").fetchone()
            if not row or not row["last_seen"]:
                return None
            return {
                "last_seen": row["last_seen"],
                "device_info": row["device_info"],
            }

    def cleanup_old(self, hours: int = 24) -> int:
        with self._conn() as db:
            cur = db.execute(
                """
                DELETE FROM device_commands
                WHERE completed_at IS NOT NULL
                  AND datetime(completed_at) < datetime('now', ?)
                """,
                (f"-{hours} hours",),
            )
            return cur.rowcount

    def start_live_session(self, command_id: str) -> None:
        with self._conn() as db:
            row = db.execute("SELECT id FROM live_session WHERE id = 1").fetchone()
            if row:
                db.execute(
                    """
                    UPDATE live_session
                    SET active = 1, command_id = ?, started_at = ?, frame_count = 0
                    WHERE id = 1
                    """,
                    (command_id, _now()),
                )
            else:
                db.execute(
                    """
                    INSERT INTO live_session (id, active, command_id, started_at, frame_count)
                    VALUES (1, 1, ?, ?, 0)
                    """,
                    (command_id, _now()),
                )

    def stop_live_session(self) -> dict | None:
        with self._conn() as db:
            row = db.execute("SELECT * FROM live_session WHERE id = 1").fetchone()
            if not row:
                return None
            db.execute(
                "UPDATE live_session SET active = 0 WHERE id = 1",
            )
            return {
                "command_id": row["command_id"],
                "frame_count": row["frame_count"],
                "started_at": row["started_at"],
            }

    def get_live_session(self) -> dict | None:
        with self._conn() as db:
            row = db.execute("SELECT * FROM live_session WHERE id = 1").fetchone()
            if not row or not row["active"]:
                return None
            return {
                "active": bool(row["active"]),
                "command_id": row["command_id"],
                "started_at": row["started_at"],
                "frame_count": row["frame_count"],
            }

    def add_live_frame(self, filename: str) -> int:
        with self._conn() as db:
            cur = db.execute(
                "INSERT INTO live_frames (filename, created_at) VALUES (?, ?)",
                (filename, _now()),
            )
            db.execute(
                "UPDATE live_session SET frame_count = frame_count + 1 WHERE id = 1",
            )
            return cur.lastrowid or 0

    def poll_live_frames(self, limit: int = 10) -> list[dict]:
        with self._conn() as db:
            rows = db.execute(
                """
                SELECT id, filename, created_at FROM live_frames
                WHERE sent = 0
                ORDER BY created_at ASC LIMIT ?
                """,
                (limit,),
            ).fetchall()
            return [
                {"id": r["id"], "filename": r["filename"], "created_at": r["created_at"]}
                for r in rows
            ]

    def mark_frame_sent(self, frame_id: int) -> None:
        with self._conn() as db:
            db.execute("UPDATE live_frames SET sent = 1 WHERE id = ?", (frame_id,))

    def cleanup_live_frames(self, hours: int = 1) -> int:
        with self._conn() as db:
            cur = db.execute(
                """
                DELETE FROM live_frames
                WHERE sent = 1
                  AND datetime(created_at) < datetime('now', ?)
                """,
                (f"-{hours} hours",),
            )
            return cur.rowcount