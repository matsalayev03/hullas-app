#!/usr/bin/env python3
"""Add /api/ proxy to hullas nginx config."""
from pathlib import Path

path = Path("/etc/nginx/sites-enabled/hullas")
text = path.read_text()

block = """
    location /api/ {
        proxy_pass http://127.0.0.1:5555/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        client_max_body_size 50M;
    }
"""

if "location /api/" not in text:
    text = text.replace(
        "    location / {\n        try_files $uri $uri/ =404;\n    }",
        block + "\n    location / {\n        try_files $uri $uri/ =404;\n    }",
    )
    path.write_text(text)
    print("nginx patched OK")
else:
    print("nginx already patched")