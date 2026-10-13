package org.hermes.duplex.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class AudioCaptureEngine(private val context: Context) {

    private val isRecording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null

    @OptIn(DelicateCoroutinesApi::class)
    private val audioDispatcher = newSingleThreadContext("HermesAudioEngineThread")
    private val audioScope = CoroutineScope(audioDispatcher + SupervisorJob())

    private val sampleRate = 24000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
    private val frameSizeBytes = 960 // 20ms

    private val ringBuffer = CircularAudioRingBuffer(4)

    @SuppressLint("MissingPermission")
    fun startRecording(onAudioChunk: (ByteArray) -> Unit) {
        if (isRecording.getAndSet(true)) return

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
        val bufferSize = maxOf(minBufferSize, frameSizeBytes * 4)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            channelConfig,
            audioEncoding,
            bufferSize
        )

        audioRecord?.startRecording()

        audioScope.launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            val rawBuffer = ByteArray(frameSizeBytes)
            val outputFrame = ByteArray(frameSizeBytes)

            while (isRecording.get()) {
                val bytesRead = audioRecord?.read(rawBuffer, 0, frameSizeBytes) ?: -1
                if (bytesRead == frameSizeBytes) {
                    ringBuffer.write(rawBuffer, 0, bytesRead)
                    if (ringBuffer.readFrame(outputFrame)) {
                        onAudioChunk(outputFrame.copyOf())
                    }
                } else if (bytesRead < 0) {
                    break
                }
            }
        }
    }

    fun stopRecording() {
        isRecording.set(false)
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            // Safe cleanup
        } finally {
            audioRecord = null
            ringBuffer.clear()
        }
    }
}
