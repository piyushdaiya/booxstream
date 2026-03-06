# booxcpy CLI specification

booxcpy is the host tool used to mirror Boox devices.

The goal is a **scrcpy-like experience**:

```
booxcpy
```

One command → mirroring starts.

---

# Basic usage

```
booxcpy
```

Steps performed automatically:

1. Detect adb device
2. Install APK if missing
3. Start BooxStream activity
4. Set adb port forward
5. Start video player

---

# Recording

Record with auto filename:

```
booxcpy --record
```

Example:

```
booxstream_20260304_180000.ivf
```

---

Record with custom filename:

```
booxcpy --record lecture.ivf
```

---

# CLI Options

```
--record [file]
    record the stream

--fps N
    capture fps (default: 12)

--bitrate N
    encoder bitrate

--size WxH
    capture resolution

--serial DEVICE
    select adb device

--no-play
    record only

--install
    force install APK

--wireless
    use adb over TCP

--help
    show help
```

---

# Example commands

Mirror device:

```
booxcpy
```

Record session:

```
booxcpy --record
```

Mirror at higher fps:

```
booxcpy --fps 20
```

Record without displaying:

```
booxcpy --record session.ivf --no-play
```

---

# Internals

booxcpy performs:

```
adb forward tcp:27183 localabstract:booxstream_ivf
```

Then reads IVF stream:

```
tcp://127.0.0.1:27183
```

The stream is VP8 inside IVF container.