# BooxStream Architecture

BooxStream is a **scrcpy-style screen mirroring system** designed specifically for **Boox e-ink Android devices**.

Its architecture is intentionally split into two parts:

1. **Android companion app** on the device
2. **Go host client** on the desktop/laptop

This split keeps device-specific capture and encoding logic on Android, while the host remains lightweight, portable, and easy to debug.

---

## High-Level Design

BooxStream uses the following pipeline:

```text
Boox Device Screen
    │
    ▼
MediaProjection
    │
    ▼
VirtualDisplay
    │
    ▼
MediaCodec VP8 Encoder
    │
    ▼
IVF stream over localabstract socket
    │
    ▼
ADB port forwarding
    │
    ▼
booxcpy (Go CLI)
    │
    ├── ffplay live mirroring
    └── optional IVF recording
```

The host does **not** capture the screen directly.  
Instead, the Android app performs capture and encoding locally, then publishes the encoded IVF stream to the host via **ADB port forwarding**.

---

## Design Goals

BooxStream was built with the following goals:

- **simple, understandable architecture**
- **low-refresh-rate mirroring for e-ink devices**
- **minimal CPU and bandwidth waste**
- **easy recording of demos**
- **cross-platform host support**
- **standard tool compatibility through IVF**
- **practical diagnostics for real hardware debugging**

The project is optimized for **readability, repeatability, and debuggability** rather than full-motion remote desktop performance.

---

## Why This Architecture Exists

Boox e-ink devices behave differently from normal Android phones and tablets.

### E-ink-specific constraints

- effective frame rates are much lower than LCD/OLED devices
- full-page redraws are common
- page turns create large high-contrast changes
- vendor ROMs may aggressively stop background services
- media pipeline behavior can vary by device and firmware

A generic mirroring stack designed for high-refresh displays is often more complex than necessary for this environment.

BooxStream therefore favors:

- a small Android capture app
- a simple encoded stream
- a host client that uses existing media tools
- explicit diagnostics for tuning and debugging

---


## Architecture Diagram
![architecure](/docs/architecture-diagram.png)
---

## System Components

## 1. Android Companion App

The Android app is responsible for:

- requesting **MediaProjection** permission
- creating the **VirtualDisplay**
- configuring the **MediaCodec VP8 encoder**
- wrapping encoded frames into **IVF**
- publishing the stream on a **localabstract** socket
- handling lifecycle, diagnostics, and cleanup

### Key Android modules

- `MainActivity`
  - UI entry point
  - receives host configuration
  - starts/stops mirroring
  - exposes runtime stats
- `HostCommandReceiver`
  - receives config from host through explicit broadcast
- `Vp8IvfStreamService`
  - foreground service
  - owns the encoder, socket server, stats, and teardown path

### Why Android owns capture and encode

Android screen capture requires platform APIs such as:

- `MediaProjection`
- `VirtualDisplay`
- `MediaCodec`

These APIs only exist on-device, so capture and encoding must be performed by the Android app itself.

---

## 2. Host Client (`booxcpy`)

The host client is a Go CLI that manages the device session.

Responsibilities:

- discover/select the ADB device
- ensure the BooxStream APK is installed
- stop stale sessions
- set up `adb forward`
- launch the Android app
- send stream configuration
- wait for a decoder-safe IVF stream start
- play via `ffplay`
- optionally record the IVF stream
- print live host-side stream stats

### Why Go

Go was chosen for the host because it provides:

- easy cross-platform builds
- simple CLI development
- strong process and I/O primitives
- straightforward concurrency for session management

---

## Core Data Path

## 1. MediaProjection

After the user approves the Android system capture prompt, the app obtains a `MediaProjection` instance.

This is the entry point for screen capture.

## 2. VirtualDisplay

The app creates a `VirtualDisplay` sized according to the selected session parameters, for example:

```text
960x540
1024x600
1280x720
```

The `VirtualDisplay` renders into the encoder input surface.

## 3. MediaCodec VP8 Encoder

The encoder is configured for:

- VP8 video
- target width/height
- target fps
- target bitrate

The encoded output is consumed via `MediaCodec.Callback`.

## 4. IVF Wrapping

Each encoded VP8 frame is wrapped into a simple IVF stream.

IVF was chosen because it is:

- extremely simple
- easy to parse
- easy to record
- directly supported by FFmpeg tooling

## 5. Local Socket Export

The Android app publishes the stream on:

```text
localabstract:booxstream_ivf
```

This keeps the stream local to the device and avoids exposing any network listener.

## 6. ADB Forwarding

The host forwards the Android local socket to localhost:

```text
tcp:27183 -> localabstract:booxstream_ivf
```

This provides a simple, secure transport without requiring a custom network service.

## 7. Host Playback / Recording

The host waits for:

- a valid IVF stream header
- a decoder-safe initial keyframe

Then it:

- mirrors to `ffplay`
- optionally records the raw `.ivf` stream
- prints live transfer stats

---

## Session Lifecycle

A BooxStream session typically follows this sequence:

```text
1. Host discovers ADB device
2. Host verifies/install APK
3. Host stops stale service if needed
4. Host creates adb forward
5. Host launches MainActivity
6. Host sends config via broadcast
7. User approves MediaProjection prompt
8. Android foreground service starts
9. Encoder starts and local socket opens
10. Host connects and waits for safe stream start
11. Stream begins (mirror and/or record)
12. User or host stops session
13. Service tears down encoder/socket/projection
14. Notification is cleared
15. Host removes adb forward
```

This lifecycle is intentionally explicit so sessions are easier to debug on vendor Android builds.

---

## Host-to-Device Control Flow

The host and Android app communicate in two stages.

## Stage 1: Bring app to foreground

The host launches:

```text
io.github.piyushdaiya.booxstream/.MainActivity
```

This ensures the app is visible and able to present the capture permission flow.

## Stage 2: Send capture configuration

The host sends an explicit broadcast to `HostCommandReceiver` with:

- autostart flag
- width
- height
- fps
- bitrate

This decouples app launch from capture configuration and avoids relying on fragile OEM-specific intent behavior.

---

## Presets and Tuning

BooxStream supports simple presets to make device-specific tuning easier.

### Current presets

- `leaf3c`
  - `960x540`
  - `8 fps`
  - `900000 bitrate`
- `noteair`
  - `1024x600`
  - `6 fps`
  - `1200000 bitrate`
- `custom`
  - manual width / height / fps / bitrate

### Why presets matter

E-ink mirroring quality depends heavily on:

- screen size
- panel resolution
- page redraw behavior
- codec stability
- firmware behavior

Presets are not universal truth. They are practical defaults based on tested device behavior.

---

## Diagnostics

BooxStream exposes diagnostics on both sides of the pipeline.

## Android-side diagnostics

The Android service can report:

- output fps
- output kbps
- total frames
- total bytes
- time since last keyframe
- dropped frames before first keyframe
- write errors

Example:

```text
stats fpsOut=5.99 kbpsOut=297 totalFrames=28 totalBytes=169365 sinceKfMs=728 lastFrameBytes=746 droppedPreKf=0 writeErrors=0
```

## Host-side diagnostics

The host CLI reports:

- connection details
- live fps estimate
- kbps estimate
- total frames seen
- total transferred size

Example:

```text
[stats] mirror elapsed=10s fps=3.00 kbps=265 frames=62 total=0.33MB
```

These diagnostics are especially useful for e-ink devices where actual output behavior is driven by **screen redraw intensity**, not just target encoder settings.

---

## Why the Host Waits for a Decoder-Safe Start

The host does not begin playback immediately after seeing an IVF header.

Instead, it waits for:

- a valid IVF stream
- the first usable VP8 keyframe

This avoids starting playback on an interframe-only sequence and reduces decoder startup issues.

This startup path improved reliability compared with naïvely attaching playback immediately after socket connection.

---

## Service Lifecycle and Cleanup

The Android stream runs inside a **foreground service**.

This service is responsible for:

- creating the encoder and virtual display
- managing the socket server
- tracking host connection state
- exposing stream stats
- cleaning up correctly on stop

Recent lifecycle cleanup improvements include:

- explicit stop action support
- idempotent teardown
- deterministic notification cleanup
- proper socket / codec / projection release

These changes were important on Boox devices, where stale notifications and partial teardown could otherwise leave the system in a confusing state.

---

## Boox ROM Behavior

One of the biggest practical challenges on real hardware is OEM process management.

Some Boox firmware builds may aggressively stop background services unless app optimization settings are adjusted.

### Required device setting

If streaming freezes or stops unexpectedly, enable:

- **Apps**
- **BooxStream**
- right-click **BooxStream**
- **Optimize**
- **Others**
- **Stay active in the background** → **On**

Without this, the foreground streaming service may still be treated as idle/background work by the ROM.

This is a device-operational requirement, not just a code issue.

---

## Why Quality Still Varies on E-ink

Even with a stable streaming pipeline, page turns and redraws can still cause temporary pixelation.

Reasons include:

- large high-contrast scene changes
- low effective frame cadence
- compressed full-screen redraws
- vendor encoder behavior
- e-ink display modes and ghosting characteristics

BooxStream is best understood as:

- **stable demo mirroring**
- **recording support**
- **diagnostic tooling for e-ink app development**

It is not trying to become a perfect high-motion remote desktop system.

---

## Security Model

BooxStream uses **ADB port forwarding** rather than a general-purpose network listener.

Properties:

- forwarded port is bound to `127.0.0.1` on the host
- no external network service is exposed by default
- Android-side stream stays inside the device until forwarded over ADB

This makes the default setup simple and relatively low-risk for local development workflows.

---

## Repository Mapping

```text
android/                        Android companion app
android/app/                    Main UI, service, receiver
android/core/                   shared Android-side utilities
host/booxcpy/                   Go host CLI
docs/                           diagrams and documentation assets
demos/                          recorded demo media
ARCHITECTURE.md                 this document
README.md                       public project overview
```

---

## Current Tradeoffs

BooxStream intentionally accepts several tradeoffs in exchange for simplicity and portability.

### Chosen tradeoffs

- VP8 + IVF instead of a more complex custom stream protocol
- Android-side app instead of host-only capture tooling
- ffplay-based host playback instead of a custom renderer
- simple presets instead of aggressive auto-tuning
- readable diagnostics over opaque performance tricks

These choices make the project easier to understand, maintain, and demonstrate as a portfolio-quality engineering system.

---

## Future Improvements

Potential next steps include:

- improved startup keyframe handling
- richer host-side live diagnostics
- more tested device presets
- grayscale-aware tuning for reading workflows
- stronger demo assets and performance notes
- broader device compatibility testing

---

## Summary

BooxStream is a compact but real media pipeline built for an unusual environment:

- Android MediaProjection
- VirtualDisplay
- MediaCodec VP8 encoding
- IVF framing
- local socket streaming
- ADB forwarding
- Go host orchestration
- live diagnostics on real Boox hardware

The architecture is intentionally simple, but the engineering challenges are real: service lifecycle, OEM behavior, e-ink redraw constraints, and startup sequencing all matter.

That combination is exactly what makes BooxStream a useful tool for e-ink application development — and a strong systems-oriented portfolio project.