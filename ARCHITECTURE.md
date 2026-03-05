# BooxStream Architecture

BooxStream consists of two main components:
1. Android streaming service
2. Host client (booxcpy)

---

## Overview

Android Device (MediaProjection + Encoder)
  -> ADB Port Forward
  -> Host Client (booxcpy)
  -> Decoder / Player

---

## Android Pipeline

MediaProjection
-> Surface capture
-> Hardware encoder (VP8)
-> IVF stream
-> Local socket (localabstract)

---

## Host Pipeline

adb forward
-> TCP stream
-> IVF parser
-> playback (ffplay) and/or recording

---

## Modules

Android:
- stream/
- codec/
- ui/

Host:
- host/booxcpy (CLI)
- adb helpers
- stream reader / recorder

---

## Goals
- low latency
- minimal dependencies
- simple architecture
- cross-platform host client