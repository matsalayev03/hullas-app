#!/bin/bash
set -euo pipefail
cd /opt/hullas

.venv/bin/pip install flask -q
mkdir -p /tmp/hullas_uploads

if ! grep -q '^DEVICE_TOKEN=' .env; then
    TOKEN=$(python3 -c 'import secrets; print(secrets.token_hex(24))')
    echo "DEVICE_TOKEN=$TOKEN" >> .env
else
    TOKEN=$(grep '^DEVICE_TOKEN=' .env | cut -d= -f2-)
fi

grep -q '^DEVICE_API_URL=' .env || echo 'DEVICE_API_URL=http://127.0.0.1:5555' >> .env
grep -q '^DEVICE_DB_PATH=' .env || echo 'DEVICE_DB_PATH=/opt/hullas/device.db' >> .env
grep -q '^UPLOAD_DIR=' .env || echo 'UPLOAD_DIR=/tmp/hullas_uploads' >> .env
grep -q '^DEVICE_TIMEOUT=' .env || echo 'DEVICE_TIMEOUT=90' >> .env

cat > /etc/systemd/system/hullas-device.service << 'EOF'
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
EOF

systemctl daemon-reload
systemctl enable hullas-device
systemctl restart hullas-device

curl -s http://127.0.0.1:5555/health
echo ""
echo "DEVICE_TOKEN=$TOKEN"