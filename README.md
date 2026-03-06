# BooxStream

<p align="center">
<img src="docs/banner.png" width="800">
</p>

<p align="center">

[![CI](https://github.com/piyushdaiya/booxstream/actions/workflows/ci.yml/badge.svg)](https://github.com/piyushdaiya/booxstream/actions/workflows/ci.yml)
![Release](https://github.com/piyushdaiya/booxstream/actions/workflows/release.yml/badge.svg)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![GitHub Release](https://img.shields.io/github/v/release/piyushdaiya/booxstream)](https://github.com/piyushdaiya/booxstream/releases)
![Downloads](https://img.shields.io/github/downloads/piyushdaiya/booxstream/total)

</p>

<p align="center">

**Scrcpy-style screen mirroring optimized for Boox e-ink devices**

</p>

<p align="center">

![platform](https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux-blue)
![android](https://img.shields.io/badge/android-8%2B-green)
![language](https://img.shields.io/badge/host-Go-blue)

</p>

---

# Overview

**BooxStream** is a lightweight screen mirroring system designed specifically for **Boox e-ink devices**.

It works similarly to **scrcpy**, but focuses on the unique constraints of **e-ink displays**:

- low refresh rates
- reduced motion artifacts
- minimal CPU usage
- stable encoding on vendor Android builds

BooxStream consists of two components:

```
Android App (BooxStream APK)
Host Client (booxcpy CLI)
```

The Android app captures the screen via **MediaProjection**, encodes it using **VP8**, and streams the video through **ADB port forwarding**.

The host tool (**booxcpy**) receives the stream and can:

- display the screen
- record the stream
- pipe the stream to external tools

---
---

# Why BooxStream Exists

BooxStream was created to solve a practical problem when working with **Boox e-ink tablets**.

While tools like **scrcpy** work extremely well for normal Android devices, they are not optimized for the unique characteristics of **e-ink displays**.

E-ink screens behave very differently from LCD/OLED screens:

- refresh rates are very low
- partial refresh and ghosting occur frequently
- video pipelines optimized for 60fps displays waste CPU
- vendor Android builds sometimes have unstable encoders

As a result, using generic mirroring tools can lead to:

- unnecessary CPU usage
- unstable frame delivery
- high latency
- poor compatibility with vendor firmware

BooxStream focuses specifically on **low-refresh-rate mirroring** for e-ink devices.

---

# Why Not Just Use scrcpy?

`scrcpy` is an excellent Android mirroring tool and inspired this project.

However, scrcpy is designed for **general Android devices**, and its architecture is optimized for:

- real-time H264 video streaming
- high frame rate displays
- GPU accelerated rendering
- interactive input control

For e-ink tablets these assumptions are not always ideal.

### Limitations when used with Boox devices

1. **High refresh assumptions**

scrcpy assumes continuous frame updates and attempts to maintain high frame throughput.

E-ink displays typically refresh at **5–15 fps**, so the additional complexity of real-time streaming is unnecessary.

---

2. **Codec constraints**

scrcpy uses **H264** for streaming.

Some vendor Android builds on e-ink devices provide:

- unstable H264 encoders
- inconsistent hardware codec behavior
- limited bitrate control

Using **VP8** provides a simpler and more predictable encoding path on these devices.

---

3. **Stream transport complexity**

scrcpy sends a custom framed video stream.

While efficient, the format is not easily consumed by standard multimedia tools.

BooxStream instead uses **IVF containerized VP8**, which is supported directly by tools like:

- `ffplay`
- `ffmpeg`
- `mpv`

This makes debugging and recording significantly easier.

---

# Why IVF for the Video Stream?

BooxStream streams VP8 frames inside an **IVF container**.

IVF (Indeo Video Format) is a simple container commonly used for VP8/VP9 streams.

Advantages:

- extremely simple format
- minimal framing overhead
- easy to parse
- supported directly by FFmpeg and many players

Example:

```
ffplay -f ivf stream.ivf
```

Using IVF allows BooxStream to:

- avoid writing a custom demuxer
- simplify debugging
- make recording trivial
- integrate with existing video tools

---

# Why an Android App is Required

Unlike scrcpy, BooxStream runs a small **Android companion app** on the device.

This app handles:

- MediaProjection screen capture
- VP8 encoding
- streaming frames over a local socket

The host tool (`booxcpy`) then connects through **ADB port forwarding**.

Architecture overview:

```
Host Computer
     │
     │ adb forward
     ▼
booxcpy (Go)
     │
     ▼
BooxStream Android App
     │
MediaProjection
     │
VP8 Encoder
     │
IVF stream
```

Separating responsibilities provides several advantages:

- simpler host implementation
- easier debugging
- Android-side control over encoder settings
- compatibility across different host operating systems

---

# Design Goals

BooxStream was built with a few core goals:

- **simple architecture**
- **low latency for e-ink refresh rates**
- **minimal CPU usage**
- **cross-platform host client**
- **easy integration with FFmpeg tools**

The project intentionally keeps the host client lightweight and pushes device-specific logic into the Android app.

---

# Project Structure

```
android/        BooxStream Android application
host/booxcpy    Go CLI client
docs/           documentation and architecture diagrams
```

---

# Inspiration

BooxStream is heavily inspired by the architecture of [**scrcpy**](https://github.com/Genymobile/scrcpy) 
by Genymobile.

The goal is not to replace scrcpy, but to explore a **simpler streaming pipeline optimized for e-ink devices**.

---
# Demo

Boox Leaf3C Videos
- [ivf format](/demos/booxstream_Leaf3C.ivf)
- [mkv format](/demos/booxstream_Leaf3C.mkv) (converted from ivf format for demo purposes)
- [m4a format](/demos/booxstream_Leaf3C.m4a) (converted from ivf format for demo purposes)
---

# Installation

Download the latest release from:

https://github.com/piyushdaiya/booxstream/releases

## Host Binary

| Platform | Download |
|--------|--------|
| Linux x64 | `booxcpy-linux-amd64.tar.gz` |
| macOS x64 | `booxcpy-darwin-amd64.tar.gz` |
| Windows x64 | `booxcpy-windows-amd64.zip` |

Extract and place the binary somewhere in your `PATH`.

Example:

```bash
tar -xzf booxcpy-linux-amd64.tar.gz
sudo mv booxcpy /usr/local/bin/
```

---

## Install Android App

Install the APK using adb:

```bash
adb install -r BooxStream-<version>-debug.apk
```

---

# Quick Start

Connect your Boox device via USB and run:

```bash
booxcpy
```

The device will prompt:

```
Start capturing?
```

Tap **Start now**.

Your Boox screen should appear instantly.



---
---

# macOS / Windows Security Prompts

BooxStream binaries are not code-signed.

### macOS

If macOS blocks execution:

```bash
xattr -d com.apple.quarantine booxcpy-darwin-amd64
chmod +x booxcpy-darwin-amd64
./booxcpy-darwin-amd64
```

Or right-click → **Open**.

### Windows

If SmartScreen warns:

```
More info → Run anyway
```

### Linux

Linux typically requires only executable permission:

```bash
chmod +x booxcpy-linux-amd64
```

---
# Recording

Record the screen to a file:

```bash
booxcpy --record
```

Example output:

```
booxstream_20260304_171200.ivf
```

Custom filename:

```bash
booxcpy --record lecture.ivf
```

---

# Command Line Options

```
booxcpy --help
```

Common options:

```
--record [file]     record stream
--fps 12            override fps
--bitrate 900000    override bitrate
--size 1280x720     override resolution
--serial DEVICE     choose adb device
--no-play           record only
```

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

Detailed architecture:

```
docs/ARCHITECTURE.md
```

---

# Screenshots

### Mirroring
Coming soon!

### Android UI
Coming soon!


---

# Debugging the Stream

Developers can inspect the raw stream manually.

```
adb forward --remove tcp:27183 2>/dev/null
adb forward tcp:27183 localabstract:booxstream_ivf
```

Then play with ffplay:

```
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
android/app/build/outputs/apk/debug/
```

---

## Host

```
cd host/booxcpy
go build
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

- Android e-ink tablets
- Android e-ink phones

Not compatible:

```
Kobo readers
non-Android e-ink devices
```

---

# Security

BooxStream communicates through **ADB port forwarding**.

The forwarded port is bound to:

```
127.0.0.1
```

No external network services are exposed.

See:


[SECURITY.md](/SECURITY.md)


---

# Contributing

Contributions are welcome.

Please read:

[CONTRIBUTING.md](/CONTRIBUTING.md)

[CODE_OF_CONDUCT.md](/CODE_OF_CONDUCT.md)


---

# License

Licensed under the **Apache License 2.0**.

See:


[LICENSE](/LICENSE)

[NOTICE](/NOTICE)


---

# Author

Piyush Daiya

GitHub:

```
https://github.com/piyushdaiya
```

---

## Trademark Notice

BOOX is a trademark of Onyx International Inc.

BooxStream is an independent open-source project and is not affiliated with,
endorsed by, or sponsored by Onyx International Inc.

---

The project adapts the same philosophy specifically for **e-ink devices**.