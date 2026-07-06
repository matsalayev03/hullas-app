#!/usr/bin/env python3
"""Patch /opt/hullas/bot.py for device commands."""
from pathlib import Path

path = Path("/opt/hullas/bot.py")
text = path.read_text()

menu_add = '''    ("screenshot", "Ekran nusxasi"),
    ("live_start", "Live ekran (2-3 sek)"),
    ("live_stop", "Live to'xtatish"),
    ("cam_back", "Orqa kamera"),
    ("cam_front", "Old kamera"),
    ("record", "Audio yozish: /record 15"),
    ("location", "GPS lokatsiya"),
    ("device", "Telefon holati"),
'''

if '("screenshot"' not in text:
    text = text.replace(
        '    ("start", "Yordam / barcha buyruqlar"),\n]',
        menu_add + '    ("start", "Yordam / barcha buyruqlar"),\n]',
    )

if '("live_start"' not in text:
    text = text.replace(
        '    ("screenshot", "Ekran nusxasi"),\n',
        '    ("screenshot", "Ekran nusxasi"),\n'
        '    ("live_start", "Live ekran (2-3 sek)"),\n'
        '    ("live_stop", "Live to\'xtatish"),\n',
    )

start_add = (
    "/screenshot — Ekran nusxasi\n"
    "/live_start — Live ekran (har 2-3 sek)\n"
    "/live_stop — Live to'xtatish\n"
    "/cam_back — Orqa kamera\n"
    "/cam_front — Old kamera\n"
    "/record 15 — Audio yozish\n"
    "/location — GPS lokatsiya\n"
    "/device — Telefon holati\n"
)

if "/live_start —" not in text:
    text = text.replace(
        '"/screenshot — Ekran nusxasi\\n"',
        '"/screenshot — Ekran nusxasi\\n"\n'
        '            "/live_start — Live ekran (har 2-3 sek)\\n"\n'
        '            "/live_stop — Live to\'xtatish\\n"',
    )

old_reg = """    if _APP_HANDLERS_AVAILABLE:
        try:
            account1_id = int(os.environ.get("ACCOUNT1_ID", 0))
            if account1_id:
                register_app_handlers(client, db, owner_id, account1_id)
                log.info("App handlers ro'yxatga olindi")
        except Exception:
            log.exception("App handlers ro'yxatga olinishda xato")"""

new_reg = """    if _APP_HANDLERS_AVAILABLE:
        try:
            register_app_handlers(client, db, owner_id)
            log.info("Device handlers ro'yxatga olindi")
        except Exception:
            log.exception("Device handlers ro'yxatga olinishda xato")"""

if old_reg in text:
    text = text.replace(old_reg, new_reg)

path.write_text(text)
print("bot.py patched OK")