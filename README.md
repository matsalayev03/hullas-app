# Hullas Remote Monitor APK

Account 1 telefoninida orqa fonda ishlaydigan app.

Screenshot, kamera, audio, lokatsiya — Bot orqali.

## Quick Start

### GitHub Actions (Automated Build)

1. **Repo yaratish:** GitHub'da yangi repo `hullas-app`
2. **SSH setup:** Repo'da push qilish uchun SSH key (githubga SSH sozlangan bo'lsa)
3. **Push:** Bu repo'ni clone, keyin push qilish
4. **APK:** GitHub Actions avtomatik build qiladi → `Actions` tab'da download

```bash
git remote add origin https://github.com/matsalayev/hullas-app.git
git add .
git commit -m "Init: Hullas app Kivy + Buildozer"
git branch -M main
git push -u origin main
```

GitHub Actions ishga tushadi, 10-15 minutda APK build bo'ladi.

### Local Build (Optional)

```bash
pip install buildozer cython kivy
buildozer android debug
# bin/Hullas*.apk ready
```

## Setup (Account 1 telefoninida)

### 1. APK o'rnatish

GitHub Actions `Actions` tab'dan APK download → Account 1 telefoniniga o'rnatish.

### 2. Environment setup

```bash
export API_ID=123456
export API_HASH=abc...
export BOT_USER_ID=987654321

# Kivy app ishga tushadi
```

### 3. Permissions

- Camera
- Audio (microphone)
- Location (GPS)
- Internet

## Bot Commands

Bot'dan:
- `/screenshot` — Ekran nusxasi
- `/photo` — Kamera rasmi
- `/record 15` — 15 sekundlik audio
- `/location` — GPS lokatsiyasi

## Troubleshooting

| Muammo | Yechim |
|--------|--------|
| APK download link yo'q | `Actions` tab → latest run → artifacts → download |
| Bot'dan xabar kelmaydi | `API_ID`, `API_HASH`, `BOT_USER_ID` to'g'ri ekanini tekshirish |
| App crashing | Logs tekshirish: Termux → `logcat` |

---

**Build status:** [![Build APK](https://github.com/matsalayev/hullas-app/actions/workflows/build.yml/badge.svg)](https://github.com/matsalayev/hullas-app/actions)
