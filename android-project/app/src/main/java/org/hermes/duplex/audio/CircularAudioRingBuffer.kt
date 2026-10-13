package org.hermes.duplex.audio

import java.util.concurrent.atomic.AtomicInteger

/**
 * Lock-free circular ring buffer specifically tuned for
 * 20ms (960 byte) audio frames at 24kHz 16-bit Mono.
 * ZERO heap allocations after initialization.
 */
class CircularAudioRingBuffer(capacityFrames: Int = 4) {
    private val frameSize = 960
    private val totalCapacity = capacityFrames * frameSize
    private val buffer = ByteArray(totalCapacity)

    private val writeIndex = AtomicInteger(0)
    private val readIndex = AtomicInteger(0)

    fun write(data: ByteArray, offset: Int, length: Int): Boolean {
        val currentWrite = writeIndex.get()
        val currentRead = readIndex.get()

        val available = if (currentWrite >= currentRead) {
            totalCapacity - (currentWrite - currentRead)
        } else {
            currentRead - currentWrite
        }

        if (available < length) return false

        for (i in 0 until length) {
            val idx = (currentWrite + i) % totalCapacity
            buffer[idx] = data[offset + i]
        }
        writeIndex.set((currentWrite + length) % totalCapacity)
        return true
    }

    fun readFrame(dest: ByteArray): Boolean {
        if (dest.size < frameSize) return false
        val currentRead = readIndex.get()
        val currentWrite = writeIndex.get()

        val available = if (currentWrite >= currentRead) {
            currentWrite - currentRead
        } else {
            totalCapacity - (currentRead - currentWrite)
        }

        if (available < frameSize) return false

        for (i in 0 until frameSize) {
            val idx = (currentRead + i) % totalCapacity
            dest[i] = buffer[idx]
        }
        readIndex.set((currentRead + frameSize) % totalCapacity)
        return true
    }

    fun clear() {
        writeIndex.set(0)
        readIndex.set(0)
    }
}
