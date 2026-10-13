# PROTOCOL.md - WebSocket JSON Specification

Version: 1.4-duplex
Transport: WebSocket (WSS / WS with local bridge token)

## Message Envelopes
\`\`\`json
{
  "type": "<message_type>",
  "id": "<uuid-v4>",
  "timestamp": 1728840000000,
  "payload": { ... }
}
\`\`\`

### 1. Handshake & Session Init (\`session.init\`)
\`\`\`json
{
  "type": "session.init",
  "id": "e4b37012-70dc-4a57-b08e-8a169b50e301",
  "timestamp": 1728840000000,
  "payload": {
    "clientVersion": "0.79-poc",
    "protocolVersion": "1.4-duplex",
    "deviceModel": "Pixel 8 Pro",
    "audioFormat": {
      "codec": "OPUS",
      "sampleRate": 24000,
      "channels": 1,
      "frameSizeMs": 20
    },
    "features": ["barge_in", "vad_local", "interruption"]
  }
}
\`\`\`

### 2. Turn Handshake (\`turn.start\` & \`turn.end\`)
\`\`\`json
{
  "type": "turn.start",
  "id": "78280f92-564b-4c07-b3a5-2964bfa1e421",
  "timestamp": 1728840002150,
  "payload": {
    "turnId": "turn_001",
    "trigger": "voice_detected",
    "vadConfidence": 0.94
  }
}
\`\`\`

### 3. Audio Streaming (\`audio.chunk\`)
Binary payload or base64 Opus frames:
\`\`\`json
{
  "type": "audio.chunk",
  "id": "c1a9382f-2391-45bc-8a71-66d412854910",
  "timestamp": 1728840002170,
  "payload": {
    "turnId": "turn_001",
    "sequence": 1,
    "opusBase64": "<opus_bytes>",
    "isFinal": false
  }
}
\`\`\`

### 4. Interruption / Barge-in (\`interruption.detected\`)
\`\`\`json
{
  "type": "interruption.detected",
  "id": "8f8b0312-d912-4eb2-a634-11884cba7993",
  "timestamp": 1728840005400,
  "payload": {
    "interruptedTurnId": "turn_000",
    "audioPositionMs": 1420
  }
}
\`\`\`
