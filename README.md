# BooxStream (booxcpy)

![banner](docs/banner.png)

![CI](https://github.com/piyushdaiya/booxstream/actions/workflows/android-ci.yml/badge.svg) ![License](https://img.shields.io/badge/license-MIT-green) ![Go](https://img.shields.io/badge/host-Go-blue)

**Scrcpy-style mirroring optimized for Boox e-ink devices**

![platform](https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux-blue) ![android](https://img.shields.io/badge/android-8%2B-green) ![license](https://img.shields.io/badge/license-MIT-yellow) ![language](https://img.shields.io/badge/host-Go-blue)

---

# Overview

**BooxStream** is a lightweight screen mirroring system for **Boox e-ink devices**.

It works similarly to **scrcpy**, but focuses on the specific needs of **e-ink displays**:

• lower refresh rates  
• reduced motion artifacts  
• stable encoding on vendor Android builds  

The project consists of two parts:

```
Android APK  +  Host CLI Tool
```

The Android app captures the screen and encodes it as **VP8**, while the host tool displays or records the stream.

---

# Key Features

• One-command mirroring  
• Works on **Windows / macOS / Linux**  
• **No root required**  
• Low-latency **VP8 video streaming**  
• Optional **video recording**  
• Minimal Android UI  
• Host tool written in **Go**

---

# Architecture

```
┌────────────────────────────┐
│        Host Computer       │
│                            │
│  booxcpy (Go CLI)          │
│                            │
│  • launches Android app    │
│  • sets adb forward        │
│  • reads VP8 stream        │
│  • displays / records      │
│                            │
└──────────────┬─────────────┘
               │
               │ adb forward
               │ tcp:27183
               ▼
┌────────────────────────────┐
│        Boox Device         │
│                            │
│  BooxStream APK            │
│                            │
│  MediaProjection           │
│        │                   │
│        ▼                   │
│    VP8 Encoder             │
│        │                   │
│        ▼                   │
│ IVF video stream           │
│ localabstract:             │
│ booxstream_ivf             │
│                            │
└────────────────────────────┘
```

The host connects through **ADB port forwarding** and plays the stream locally.

---

# Requirements

## Computer

You need:

```
adb
```

Supported OS:

```
Windows
macOS
Linux
```

---

## Boox device

Enable:

```
Developer Options
USB Debugging
```

Then connect the device with USB.

---

# Quick Start

## 1 Install the APK

Download the latest release and install:

```
adb install -r booxstream.apk
```

---

## 2 Start mirroring

Run:

```
booxcpy
```

The Boox device will display:

```
Start capturing?
```

Tap:

```
Start now
```

The screen appears instantly on your computer.

---

# Recording

## Automatic filename

```
booxcpy --record
```

Example output:

```
booxstream_20260304_171200.ivf
```

---

## Custom filename

```
booxcpy --record lecture.ivf
```

---

# Useful Options

```
booxcpy --help
```

Typical options:

```
--record [file]     record stream
--fps 12            override fps
--bitrate 900000    override bitrate
--size 1280x720     override resolution
--serial DEVICE     choose adb device
--no-play           record only
```

If bitrate is not specified, BooxStream automatically selects a safe value.

---

# Android App UI

The Android interface is intentionally minimal.

Buttons:

```
Start Mirroring
Stop
Advanced
```

Advanced screen provides:

```
Codec probe
Streaming statistics
```

Normal users only press **Start Mirroring**.

---

# Debugging Stream

Developers can manually view the stream.

```
adb forward --remove tcp:27183 2>/dev/null
adb forward tcp:27183 localabstract:booxstream_ivf

ffplay \
  -fflags nobuffer \
  -flags low_delay \
  -framedrop \
  -f ivf \
  -i tcp://127.0.0.1:27183
```

---

# Build from Source

## Android

```
cd android
./gradlew :app:assembleDebug
```

APK output:

```
android/app/build/outputs/apk/
```

---

## Host tool

```
cd host
go build ./...
```

Binary produced:

```
booxcpy
```

---

# Device Compatibility

Primary target:

```
Boox e-ink devices
```

Tested on:

```
Boox Leaf 3C
```

Likely compatible:

```
Android e-ink tablets
Android e-ink phones
```

Not compatible:

```
Kobo readers
non-Android e-ink devices
```

These cannot run Android APKs.

---

# Security

The stream runs over **ADB port forwarding**.

The port is bound to:

```
127.0.0.1
```

No network service is exposed externally.

---

# Roadmap

Planned improvements:

• built-in player (remove ffplay dependency)  
• wireless ADB support  
• refresh-aware encoding for e-ink  
• WebRTC streaming option  
• package managers (brew / apt / scoop)

---

# License

MIT License

```
MIT License

Copyright (c) 2026 Piyush Daiya

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
```

---

# Author

Piyush Daiya

GitHub:

```
https://github.com/piyushdaiya
```

---

# Inspiration

This project is inspired by:

```
scrcpy


by Genymobile.
```
BooxStream adapts the same philosophy specifically for **e-ink devices**.