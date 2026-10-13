# Hermes Duplex Android App (org.hermes.duplex)

Modernized, low-latency duplex voice and chat client for the Hermes Mac bridge server.

- **Package:** `org.hermes.duplex`
- **Version:** `v0.79-poc`
- **Language:** Kotlin
- **Minimum SDK:** API 26 (Android 8.0)
- **Target SDK:** API 34 (Android 14)
- **UI Framework:** Jetpack Compose (Material 3)

## Key Systems Architecture
1. **Zero-Allocation Audio Loop:** `CircularAudioRingBuffer.kt` eliminates garbage collection pauses during 24kHz 20ms audio frame streaming.
2. **Fail-Closed Security Model:** `FailClosedSecurityManager.kt` guarantees immediate hardware microphone release and socket teardown on any authentication or connection failure.
3. **Android 14 Foreground Service:** Declares and uses `FOREGROUND_SERVICE_MICROPHONE` with persistent notification channel and wake lock lifecycle.
4. **Duplex WebSocket Protocol:** Compliant with `docs/PROTOCOL.md` (JSON envelopes, Opus 24kHz / PCM 16-bit, barge-in interruption).

## Build Instructions
\`\`\`bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
\`\`\`
