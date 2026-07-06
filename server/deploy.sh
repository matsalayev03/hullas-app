#!/bin/bash
# Hullas device API deploy script
set -euo pipefail

HULLAS_DIR="/opt/hullas"
TOKEN="${DEVICE_TOKEN:-$(python3 -c 'import secrets; print(secrets.token_hex(24))')}"

echo "Deploying to $HULLAS_DIR ..."
cp device_store.py device_server.py app_handlers.py "$HULLAS_DIR/"

# .env ga DEVICE_TOKEN qo'shish
if ! grep -q '^DEVICE_TOKEN=' "$HULLAS_DIR/.env" 2>/dev/null; then
    echo "DEVICE_TOKEN=$TOKEN" >> "$HULLAS_DIR/.env"
    echo "Yangi DEVICE_TOKEN yaratildi: $TOKEN"
else
    TOKEN=$(grep '^DEVICE_TOKEN=' "$HULLAS_DIR/.env" | cut -d= -f2)
    echo "Mavjud DEVICE_TOKEN ishlatiladi"
fi

grep -q '^DEVICE_API_URL=' "$HULLAS_DIR/.env" || \
    echo 'DEVICE_API_URL=http://127.0.0.1:5555' >> "$HULLAS_DIR/.env"
grep -q '^DEVICE_DB_PATH=' "$HULLAS_DIR/.env" || \
    echo 'DEVICE_DB_PATH=/opt/hullas/device.db' >> "$HULLAS_DIR/.env"
grep -q '^UPLOAD_DIR=' "$HULLAS_DIR/.env" || \
    echo 'UPLOAD_DIR=/tmp/hullas_uploads' >> "$HULLAS_DIR/.env"

pip install flask -q

mkdir -p /tmp/hullas_uploads

# systemd service
cat > /etc/systemd/system/hullas-device.service << 'UNIT'
[Unit]
Description=Hullas Device API
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/hullas
EnvironmentFile=/opt/hullas/.env
ExecStart=/opt/hullas/.venv/bin/python device_server.py
Restart=on-failure
RestartSec=5
Environment=PYTHONUNBUFFERED=1

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable hullas-device
systemctl restart hullas-device
systemctl restart hullas

echo "Device API: $(curl -s http://127.0.0.1:5555/health)"
echo "DEVICE_TOKEN=$TOKEN"