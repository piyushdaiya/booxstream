# Architecture

BooxStream consists of two components:

```
Android APK
Host CLI (booxcpy)
```
---

## Architecture Diagram
![architecure](/docs/architecture-diagram.png)
---

## Android Side

The Android application:

1. Starts MediaProjection
2. Captures screen frames
3. Encodes frames using VP8
4. Writes IVF stream
5. Publishes stream to a local socket

```
localabstract:booxstream_ivf
```

---

## Host Side

The host application:

1. launches the Android streaming service
2. creates ADB forwarding

```
adb forward tcp:27183 localabstract:booxstream_ivf
```

3. reads IVF stream
4. displays or records the video

---

## Data Flow

```
MediaProjection
      │
      ▼
VP8 Encoder
      │
      ▼
IVF Stream
      │
      ▼
Local Socket
      │
      ▼
ADB Forward
      │
      ▼
booxcpy
```