# BooxStream

![banner](docs/banner.png)

![CI](https://github.com/piyushdaiya/booxstream/actions/workflows/ci.yml/badge.svg)
![Release](https://github.com/piyushdaiya/booxstream/actions/workflows/release.yml/badge.svg?branch=main&cachebust=1)
![License](https://img.shields.io/github/license/piyushdaiya/booxstream)
![GitHub release](https://img.shields.io/github/v/release/piyushdaiya/booxstream)

**Scrcpy-style screen mirroring optimized for Boox e-ink devices**

![platform](https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux-blue)
![android](https://img.shields.io/badge/android-8%2B-green)
![language](https://img.shields.io/badge/host-Go-blue)

 --

## Overview

**BooxStream** is a lightweight screen mirroring system designed specifically for **Boox e-ink devices**.

It works similarly to **scrcpy**, but focuses on the unique constraints of **e-ink displays**:

- lower refresh rates
- reduced motion artifacts
- stable encoding on vendor Android builds
- minimal overhead

The system consists of two components:

```
Android APK  +  Host CLI tool
```

The Android app captures the screen using **MediaProjection**, encodes it as **VP8**, and streams it to the host computer through **ADB port forwarding**.

The host tool (**booxcpy**) receives the stream and can display or record it.

---

## Key Features

- One-command mirroring
- Works on **Windows / macOS / Linux**
- **No root required**
- Low-latency **VP8 streaming**
- Optional **video recording**
- Minimal Android UI
- Host client written in **Go**
- Compatible with standard tools like **ffplay**

---

## Architecture

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

The host connects through **ADB port forwarding** and decodes the video stream locally.

---

## Requirements

### Computer

You need:

```
adb
```

Supported operating systems:

- Windows
- macOS
- Linux

---

### Boox Device

Enable:

```
Developer Options
USB Debugging
```

Then connect the device via USB.

---

## Installation

### Download Release

Download the latest binaries from:

https://github.com/piyushdaiya/booxstream/releases

You will find:

```
booxstream.apk
booxcpy-linux-amd64
booxcpy-darwin-amd64
booxcpy-windows-amd64.exe
```

---

### Install the Android App

```
adb install -r booxstream.apk
```

---

## Quick Start

Start mirroring:

```
booxcpy
```

The Boox device will prompt:

```
Start capturing?
```

Tap:

```
Start now
```

The screen should appear instantly on your computer.

---

## Recording

Record the stream:

```
booxcpy --record
```

Example output:

```
booxstream_20260304_171200.ivf
```

Specify custom filename:

```
booxcpy --record lecture.ivf
```

---

## Command Line Options

```
booxcpy --help
```

Common options:

```
--record [file]     record stream
--fps 12            override FPS
--bitrate 900000    override bitrate
--size 1280x720     override resolution
--serial DEVICE     choose adb device
--no-play           record only
```

If bitrate is not specified, BooxStream selects a safe default automatically.

---

## Android App UI

The Android interface is intentionally minimal.

Main buttons:

```
Start Mirroring
Stop
Advanced
```

Advanced menu provides:

```
Codec probe
Streaming statistics
```

Most users only need **Start Mirroring**.

---

## Debugging the Stream

Developers can view the raw stream manually.

```
adb forward --remove tcp:27183 2>/dev/null
adb forward tcp:27183 localabstract:booxstream_ivf
```

Then play it with ffplay:

```
ffplay \
  -fflags nobuffer \
  -flags low_delay \
  -framedrop \
  -f ivf \
  -i tcp://127.0.0.1:27183
```

---

## Build From Source

### Android

```
cd android
./gradlew :app:assembleDebug
```

APK output:

```
android/app/build/outputs/apk/debug/
```

---

### Host Tool

```
cd host/booxcpy
go build
```

Binary produced:

```
booxcpy
```

---

## Device Compatibility

Primary target:

```
Boox e-ink devices
```

Tested on:

```
Boox Leaf 3C
```

Likely compatible:

- Android e-ink tablets
- Android e-ink phones

Not compatible:

```
Kobo readers
non-Android e-ink devices
```

These devices cannot run Android APKs.

---

## Security

BooxStream communicates through **ADB port forwarding**.

The forwarded port is bound to:

```
127.0.0.1
```

This means the stream is **not exposed to external networks**.

---

## Roadmap

Planned improvements:

- built-in video player (remove ffplay dependency)
- wireless ADB support
- refresh-aware encoding for e-ink
- WebRTC streaming option
- package manager support (brew / apt / scoop)

---

## License

This project is licensed under the **Apache License 2.0**.

See:

```
LICENSE
NOTICE
```

---

## Author

**Piyush Daiya**

GitHub:

```
https://github.com/piyushdaiya
```

---

## Inspiration

This project is inspired by **scrcpy** by Genymobile.

BooxStream adapts the same philosophy specifically for **e-ink devices**.