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