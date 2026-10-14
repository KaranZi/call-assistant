package com.karan.callassistant

import android.content.Context
import java.io.File

/**
 * On-device transcription.
 *
 * PHASE 0 (this spike): returns a placeholder string so the rest of the pipeline
 * (overlay, drafts, share) can be exercised end-to-end without an ASR engine bundled.
 *
 * PHASE 1 (next): plug in an open-source on-device engine. Two realistic options, both
 * keeping call audio on the phone:
 *   - whisper.cpp (ggml) via JNI — bundle a tiny/base model, feed it the 16 kHz PCM
 *     written by [AudioRecorder]. Best accuracy, larger APK.
 *   - Vosk Android — smaller, streaming, slightly lower accuracy.
 * Android's built-in SpeechRecognizer is also an option but is tuned for live mic input
 * rather than a recorded file, so an offline model is the cleaner fit here.
 *
 * The signature below is already file-based, so wiring a real engine is a drop-in change.
 */
object Transcriber {

    fun transcribe(context: Context, audioFile: File?): String {
        if (audioFile == null || audioFile.length() == 0L) {
            return "(No audio captured. Make sure the mic permission is granted and, for " +
                "both-sides capture, that the call was on speakerphone.)"
        }
        val seconds = audioFile.length() / (AudioRecorder.SAMPLE_RATE * 2)
        return "(Placeholder transcript — captured ${audioFile.length()} bytes " +
            "(~${seconds}s) of audio. Phase 1 wires an on-device ASR engine here.)"
    }
}
