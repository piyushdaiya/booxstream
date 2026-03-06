# Threat model (MVP)

## Assets

- Screen contents (sensitive)
- Session token (auth secret)

## Primary threats

1) Accidental exposure on network
2) Unauthorized local connection / reuse of forwarded ports
3) Denial of service by repeated connects or oversized frames
4) Data leakage via logs

## Mitigations (MVP)

- Bind only to 127.0.0.1 (no 0.0.0.0)
- Require token on every connection before streaming
- Rate-limit failed handshakes + close connections quickly
- Cap max frame size and enforce strict framing
- Never log token; avoid payload logging
- Foreground service + user-visible notification while streaming