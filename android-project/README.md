# Hermes Duplex — Android (phone + watch)

Full-duplex voice and chat client for the Hermes bridge. Phone UI is Jetpack
Compose (dark-first design); the engine underneath is the real client.

| module | what |
|---|---|
| `:core` | UI-free logic shared by phone and watch: conversation store, sidebar, approvals, orb maths, speaker, timing, event log |
| `:app` | Phone app. `DuplexClient` / `DuplexService` (socket, mic, speaker), Compose screens in `ui/` |
| `:wear` | Wear OS companion (same applicationId and signing key as the phone) |

## Wire protocol

The bridge speaks a plain WebSocket, default `ws://<host>:8770/ws`:

- **up (binary):** 16 kHz mono s16le PCM, 20 ms frames (640 bytes)
- **down (binary):** PCM reply audio at the same format
- **text, both ways:** JSON objects with a `type`. Events from the bridge include
  `hello`, `state`, `transcript`, `reply_delta`, `reply`, `projects`, `chat_opened`,
  `chat_delta`, `approval_request`, `usage`, `trajectory`, `suggestions`. Commands
  include `prompt`, `stop`, `config`, `open_chat`, `approval_reply`, `usage`.
- There is no token header; the bridge is reached over LAN/Tailscale only.

The authoritative definition is `server/duplex_server.py` in the main project;
`DuplexClient.kt` is its client.

## Screens (all Compose)

Chat (markdown, links, streaming, copy / rewrite / read-aloud, CLI step
disclosure, media, private idea cards), voice stage (orb, mute, interrupt,
scrubbable caption), sidebar (engine, projects, chats / CLI sessions, usage),
approvals, suggestions, Settings (voices, switches, engine, mic tuning),
Remote machines, Usage, Timing, Debug, Voice browser.

## Build

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :app:assembleDebug :wear:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Needs `local.properties` with `sdk.dir=` (not committed).
