# Hullas Remote Agent

Ikkinchi telefonni Telegram bot orqali masofadan boshqarish.

## Arxitektura

```
Siz → @hullas_telegram_bot (/screenshot, /cam_back, ...)
         ↓ buyruq navbati (SQLite)
Server → hullas.azro.uz/api/device/poll
         ↓
Telefon APK (Hullas Agent) → bajaradi → upload
         ↓
Bot → sizga rasm/audio/lokatsiya yuboradi
```

## Bot buyruqlari

| Buyruq | Vazifa |
|--------|--------|
| `/screenshot` | Ekran nusxasi |
| `/cam_back` | Orqa kamera |
| `/cam_front` | Old kamera |
| `/record 15` | 15 sekund audio |
| `/location` | GPS + Google Maps |
| `/device` | Telefon onlaynmi? |

Mavjud buyruqlar (`/recent`, `/stats`, ...) o'z joyida.

## APK o'rnatish

1. GitHub Actions → `Build Android APK` → artifact yuklab olish
2. Ikkinchi telefonga o'rnatish
3. Sozlash:
   - **Server URL:** `https://hullas.azro.uz`
   - **Device Token:** server `.env` dagi `DEVICE_TOKEN`
4. Ruxsatlar berish (kamera, mikrofon, lokatsiya)
5. "Ekran ruxsati" — screenshot uchun bir marta
6. "Ishga tushirish" → "Ilovani yashirish"
7. Batareya: "Cheklanmagan" qilish (Samsung/Xiaomi: Autostart yoqish)

## Server (allaqachon sozlangan)

```bash
systemctl status hullas          # bot
systemctl status hullas-device   # device API :5555
curl http://127.0.0.1:5555/health
```

## Lokal build (Android)

```bash
cd android
gradle assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```