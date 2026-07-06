[app]

# App metadata
title = Hullas Remote Monitor
package.name = hullas_app
package.domain = org.hullas

# Source
source.dir = .
source.include_exts = py,png,jpg,kv,atlas

# Requirements
requirements = python3,kivy,telethon,requests,pillow,pyaudio

# Permissions
android.permissions = CAMERA,RECORD_AUDIO,ACCESS_FINE_LOCATION,INTERNET,WRITE_EXTERNAL_STORAGE,READ_EXTERNAL_STORAGE

# Features
android.features = android.hardware.camera,android.hardware.microphone,android.hardware.location

# App icon + presplash (optional)
p4a.bootstrap = sdl2

[buildozer]

# Build settings
log_level = 2
warn_on_root = 1
