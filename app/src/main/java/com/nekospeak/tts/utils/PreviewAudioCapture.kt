package com.nekospeak.tts.utils

import android.content.Context
import android.media.AudioFormat
import java.io.File
import java.io.RandomAccessFile

/**
 * Stores only audio explicitly previewed inside NekoSpeak's Voices screen.
 *
 * The Android TTS UtteranceProgressListener supplies the same PCM audio chunks
 * being played by the system. Capturing them here avoids a second TTS pass
 * when the user chooses Save or Share.
 *
 * The completed WAV is kept in app-private storage until another preview is
 * successfully captured. Incomplete attempts never replace the last WAV.
 */
class PreviewAudioCapture(context: Context) {
    private val lock = Any()
    private val exportDirectory = File(context.filesDir, "voiceovers").apply { mkdirs() }
    private val completed = File(exportDirectory, "latest_preview.wav")
    private val pending = File(exportDirectory, "preview_in_progress.tmp")

    private var armedId: String? = null
    private var writer: RandomAccessFile? = null
    private var sampleRate: Int = 0
    private var channelCount: Int = 0
    private var audioBytes: Long = 0L

    fun lastCompleted(): File? = synchronized(lock) {
        completed.takeIf { it.isFile && it.length() > WAV_HEADER_SIZE }
    }

    fun arm(utteranceId: String) = synchronized(lock) {
        cleanupPending()
        armedId = utteranceId
    }

    fun begin(utteranceId: String, rate: Int, encoding: Int, channels: Int) = synchronized(lock) {
        if (utteranceId != armedId) return@synchronized
        cleanupPending()
        if (encoding != AudioFormat.ENCODING_PCM_16BIT || rate <= 0 ||
            channels !in 1..2
        ) {
            armedId = null
            return@synchronized
        }
        try {
            val file = RandomAccessFile(pending, "rw")
            file.setLength(0)
            file.write(ByteArray(WAV_HEADER_SIZE))
            writer = file
            sampleRate = rate
            channelCount = channels
            audioBytes = 0
        } catch (_: Exception) {
            cleanupPending()
            armedId = null
        }
    }

    fun addAudio(utteranceId: String, pcm: ByteArray) = synchronized(lock) {
        if (utteranceId != armedId || pcm.isEmpty()) return@synchronized
        try {
            writer?.let {
                it.write(pcm)
                audioBytes += pcm.size
            }
        } catch (_: Exception) {
            cleanupPending()
            armedId = null
        }
    }

    fun finish(utteranceId: String): File? = synchronized(lock) {
        if (utteranceId != armedId) return@synchronized null
        val file = writer ?: run {
            cleanupPending()
            armedId = null
            return@synchronized null
        }
        try {
            if (audioBytes == 0L || audioBytes > MAX_PCM_BYTES) {
                return@synchronized null
            }
            file.seek(0)
            writeWavHeader(file, audioBytes.toInt(), sampleRate, channelCount)
            file.fd.sync()
            file.close()
            writer = null

            // Keep the previous recording unless the new one is complete.
            val next = File(exportDirectory, "ready_preview.wav")
            pending.copyTo(next, overwrite = true)
            if (completed.exists() && !completed.delete()) {
                next.delete()
                return@synchronized null
            }
            if (!next.renameTo(completed)) {
                next.copyTo(completed, overwrite = true)
                next.delete()
            }
            completed.takeIf { it.isFile && it.length() > WAV_HEADER_SIZE }
        } catch (_: Exception) {
            null
        } finally {
            cleanupPending()
            armedId = null
        }
    }

    fun abort(utteranceId: String) = synchronized(lock) {
        if (utteranceId == armedId) {
            cleanupPending()
            armedId = null
        }
    }

    private fun cleanupPending() {
        try { writer?.close() } catch (_: Exception) {}
        writer = null
        pending.delete()
        sampleRate = 0
        channelCount = 0
        audioBytes = 0
    }

    private fun writeWavHeader(file: RandomAccessFile, length: Int, rate: Int, channels: Int) {
        val bytesPerSample = 2
        val blockAlign = channels * bytesPerSample
        file.write("RIFF".toByteArray(Charsets.US_ASCII))
        file.writeIntLE(length + 36)
        file.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        file.writeIntLE(16)
        file.writeShortLE(1) // PCM
        file.writeShortLE(channels)
        file.writeIntLE(rate)
        file.writeIntLE(rate * blockAlign)
        file.writeShortLE(blockAlign)
        file.writeShortLE(16) // bits per sample
        file.write("data".toByteArray(Charsets.US_ASCII))
        file.writeIntLE(length)
    }

    private fun RandomAccessFile.writeIntLE(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }

    private fun RandomAccessFile.writeShortLE(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private companion object {
        const val WAV_HEADER_SIZE = 44
        // 4 GB WAV RIFF file limit; avoid overflow in signed Int arithmetic.
        const val MAX_PCM_BYTES = 0x7ffffff0L
    }
}
