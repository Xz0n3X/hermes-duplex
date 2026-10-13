# SERVER_CONTRACT.md - Bridge Server (Mac OS)

## IMMUTABLE CONSTRAINTS - DO NOT MODIFY

The server runs as a Python/Rust duplex audio bridge on macOS with Apple Silicon Neural Engine:
- **Port:** 8765 (WebSocket default)
- **Audio Intake:** Expects Opus at 24kHz mono (20ms frames) or PCM 16-bit 24kHz LE.
- **Heartbeat:** Ping/Pong every 15,000ms. Missed 2 pings triggers connection drop.
- **Backpressure:** Buffer capacity is 250ms of audio. Client must not flood audio chunks ahead of timestamp progression.
- **Security:** Requires `X-Hermes-Token` header in WebSocket handshake.
