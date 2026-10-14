package com.karan.callassistant

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Answers the one question we can't answer from anywhere but the phone itself:
 * does *this* device allow capturing the remote party's call audio, or will we have to
 * fall back to speakerphone?
 *
 * [deviceSummary] reports make / model / Android version.
 * [probeSources] tries to open an [AudioRecord] on each candidate audio source and reports
 * which ones initialize. Important honesty note: a source *initializing* is necessary but not
 * sufficient — on many devices VOICE_CALL/VOICE_DOWNLINK will open and then return silence.
 * The only definitive proof is a real test call, where [CallMonitorService] measures the
 * captured signal level and the post-call pop-up reports it. This probe narrows it down fast.
 */
object DeviceProbe {

    data class SourceResult(val name: String, val initialized: Boolean)

    fun deviceSummary(): String =
        "Device: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "Build: ${Build.DISPLAY}"

    private val candidates = linkedMapOf(
        "VOICE_CALL (both sides)" to MediaRecorder.AudioSource.VOICE_CALL,
        "VOICE_DOWNLINK (remote only)" to MediaRecorder.AudioSource.VOICE_DOWNLINK,
        "VOICE_UPLINK (your side)" to MediaRecorder.AudioSource.VOICE_UPLINK,
        "VOICE_COMMUNICATION" to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        "VOICE_RECOGNITION" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
        "MIC (speakerphone path)" to MediaRecorder.AudioSource.MIC
    )

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO first
    fun probeSources(context: Context): List<SourceResult> {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return listOf(SourceResult("(grant mic permission first)", false))
        }
        val minBuf = AudioRecord.getMinBufferSize(
            16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        return candidates.map { (label, source) ->
            var ok = false
            var rec: AudioRecord? = null
            try {
                rec = AudioRecord(
                    source, 16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, minBuf
                )
                ok = rec.state == AudioRecord.STATE_INITIALIZED
            } catch (_: Exception) {
                ok = false
            } finally {
                try {
                    rec?.release()
                } catch (_: Exception) {
                }
            }
            SourceResult(label, ok)
        }
    }

    /**
     * Capture source for calls: always the microphone. On stock Android 10+ phones VOICE_CALL
     * and VOICE_DOWNLINK often *open* fine and then record silence, which would lose the whole
     * call, so we no longer pick them just because they initialize. The mic gets both voices
     * on speakerphone and your voice with the phone at your ear.
     */
    @Suppress("UNUSED_PARAMETER")
    fun preferredSource(context: Context): Int = MediaRecorder.AudioSource.MIC

    fun sourceLabel(source: Int): String = when (source) {
        MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL (device-level both sides)"
        MediaRecorder.AudioSource.VOICE_DOWNLINK -> "VOICE_DOWNLINK"
        else -> "mic"
    }
}
