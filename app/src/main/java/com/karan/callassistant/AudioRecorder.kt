package com.karan.callassistant

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

/**
 * Captures audio while a call is in progress.
 *
 * The [audioSource] is chosen per device by [DeviceProbe]: where the device-level route works
 * (e.g. a rooted phone or a permissive vendor build) we try VOICE_CALL / VOICE_DOWNLINK to get
 * both sides directly; everywhere else we fall back to the MIC source, which captures both
 * sides only when the call is on **speakerphone** (the remote voice comes out of the speaker
 * and back into the mic), or the user's side otherwise.
 *
 * Why not always VOICE_CALL: since Android 10 that source returns silence or fails on virtually
 * all stock, non-rooted devices. [peakAmplitude] lets a real test call reveal whether the chosen
 * source actually produced sound or came back silent.
 *
 * Output is raw 16-bit PCM mono at 16 kHz — the format on-device speech engines expect.
 */
class AudioRecorder(
    private val outFile: File,
    private val audioSource: Int = MediaRecorder.AudioSource.MIC
) {

    private var record: AudioRecord? = null
    @Volatile private var recording = false
    private var captureThread: Thread? = null

    /** Peak 16-bit sample magnitude seen during capture (0..32767). ~0 means we got silence. */
    @Volatile var peakAmplitude = 0
        private set

    @SuppressLint("MissingPermission") // caller guarantees RECORD_AUDIO was granted
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufSize = if (minBuf > 0) minBuf * 2 else SAMPLE_RATE * 2
        val rec = try {
            AudioRecord(audioSource, SAMPLE_RATE, CHANNEL, ENCODING, bufSize)
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord($audioSource) threw; falling back to MIC", e)
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, bufSize)
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            rec.release()
            return
        }
        record = rec
        recording = true
        rec.startRecording()

        captureThread = thread(name = "audio-capture") {
            val buffer = ByteArray(bufSize)
            BufferedOutputStream(FileOutputStream(outFile)).use { out ->
                while (recording) {
                    val n = rec.read(buffer, 0, buffer.size)
                    if (n > 0) {
                        out.write(buffer, 0, n)
                        trackPeak(buffer, n)
                    }
                }
            }
        }
    }

    /** Stops capture and returns the file that was written (or null if nothing recorded). */
    fun stop(): File? {
        recording = false
        captureThread?.join(2000)
        captureThread = null
        record?.apply {
            try {
                if (state == AudioRecord.STATE_INITIALIZED) stop()
            } catch (_: IllegalStateException) {
            }
            release()
        }
        record = null
        return if (outFile.exists() && outFile.length() > 0) outFile else null
    }

    /** Track the loudest 16-bit little-endian sample in this chunk, to detect a silent source. */
    private fun trackPeak(buffer: ByteArray, bytes: Int) {
        var i = 0
        var peak = peakAmplitude
        while (i + 1 < bytes) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val mag = if (sample < 0) -sample else sample
            if (mag > peak) peak = mag
            i += 2
        }
        peakAmplitude = peak
    }

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
