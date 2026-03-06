# BooxStream Protocol v1

## Goals

- Local-only by default (127.0.0.1)
- Token-authenticated handshake
- Length-prefixed binary frames with timestamps (µs)
- Simple enough to test and fuzz for framing bugs

## Transport

TCP, server on device listens on 127.0.0.1:27183.

## Byte order

Little-endian.

## Handshake (Client -> Server): HELLO

u32 magic        = 0x584F4F42  ("BOOX" little-endian)
u16 version      = 1
u16 token_len
bytes token[token_len]

## Handshake (Server -> Client): HELLO_OK

u32 magic        = 0x584F4F42
u16 version      = 1
u16 codec_id     (1=VP8, 2=VP9, 3=H264, 4=H265)
u16 width
u16 height
u32 timebase_den (1_000_000 for microseconds)

## Frame (Server -> Client)

u32 payload_len
u64 pts_us
u8  flags        (bit0=keyframe)
u8  reserved8
u16 reserved16
bytes payload[payload_len]

## Notes

- payload_len is capped by the server (e.g. 8 MiB) to avoid memory abuse.
- token is never logged.