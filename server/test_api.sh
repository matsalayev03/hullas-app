#!/bin/bash
TOKEN=$(grep '^DEVICE_TOKEN=' /opt/hullas/.env | cut -d= -f2-)
echo "Poll (empty):"
curl -s "https://hullas.azro.uz/api/device/poll" -H "X-Device-Token: $TOKEN"
echo ""
cd /opt/hullas
.venv/bin/python << 'PY'
from pathlib import Path
from device_store import DeviceStore
s = DeviceStore(Path("device.db"))
cid = s.create_command("location", {})
print("Created:", cid)
print("Poll:", s.poll_command())
PY
echo "Poll (with cmd):"
curl -s "https://hullas.azro.uz/api/device/poll" -H "X-Device-Token: $TOKEN"
echo ""