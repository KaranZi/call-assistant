package com.karan.callassistant

import android.content.Context
import android.util.Log
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Turns a call recording into the transcript shown in the pop-up and history.
 *
 * With a Groq key saved (Follow-up drafts → Groq), the audio goes to Whisper on Groq's free
 * tier. Otherwise, or if Groq fails, the offline Vosk engine is used. On speakerphone calls the
 * offline speaker model still decides who said what: its timed, labelled utterances are lined
 * up with the Whisper segments by overlap.
 */
object CallTranscription {

    private const val TAG = "CallTranscription"

    data class Outcome(val text: String, val language: String, val engine: String)

    fun run(context: Context, audio: File, speaker: Boolean): Outcome {
        val groqKey = LlmSettings.load(context)
            .takeIf { it.provider == LlmSettings.Provider.GROQ }?.apiKey?.takeIf { it.isNotBlank() }
        var engineNote = "offline"
        if (groqKey != null) {
            try {
                return viaGroq(context, groqKey, audio, speaker)
            } catch (e: Exception) {
                Log.w(TAG, "Groq transcription failed; using offline", e)
                engineNote = "offline (Groq failed: ${e.message?.take(60)})"
            }
        }
        return offline(context, audio, speaker, engineNote)
    }

    private fun viaGroq(context: Context, key: String, audio: File, speaker: Boolean): Outcome {
        val code = when (AppSettings.language(context)) {
            Transcriber.Language.ENGLISH -> "en"
            Transcriber.Language.HINDI -> "hi"
            Transcriber.Language.AUTO -> null
        }
        val cloud = CloudTranscriber.transcribe(key, audio, code)
        val language = cloud.language.replaceFirstChar { it.uppercase() }
        if (cloud.segments.isEmpty()) return Outcome("(No speech recognised in the recording.)", language, "Groq Whisper")
        val plain = cloud.segments.joinToString(" ") { it.text }

        if (!speaker) return Outcome("${SpeakerLabeler.YOU}: $plain", language, "Groq Whisper")

        // Who said what: offline voiceprints + timings, mapped onto Whisper's segments.
        val local = try {
            Transcriber.transcribe(context, audio, withVoiceprints = true, forceLanguage = Transcriber.Language.ENGLISH)
        } catch (e: Exception) {
            Log.w(TAG, "Offline speaker pass failed", e)
            null
        }
        if (local == null || local.segments.isEmpty()) return Outcome(plain, language, "Groq Whisper")
        val localLabels = SpeakerLabeler.label(local.segments, AppSettings.voiceprint(context))
        val labels = cloud.segments.map { c -> localLabels[closest(c, local.segments)] }
        return Outcome(SpeakerLabeler.format(cloud.segments, labels), language, "Groq Whisper")
    }

    private fun offline(context: Context, audio: File, speaker: Boolean, engine: String): Outcome {
        val out = Transcriber.transcribe(context, audio, withVoiceprints = speaker)
            ?: return Outcome("(Speech model missing from this build, so no transcript.)", "", engine)
        if (out.segments.isEmpty()) return Outcome("(No speech recognised in the recording.)", out.language.label, engine)
        val text = if (speaker) {
            SpeakerLabeler.format(out.segments, SpeakerLabeler.label(out.segments, AppSettings.voiceprint(context)))
        } else {
            // With the phone at your ear the mic only hears you.
            "${SpeakerLabeler.YOU}: ${out.plainText}"
        }
        return Outcome(text, out.language.label, engine)
    }

    /** Index of the local utterance that overlaps [c] most, or the nearest one if none overlap. */
    private fun closest(c: Transcriber.Segment, local: List<Transcriber.Segment>): Int {
        var best = 0
        var bestOverlap = 0.0
        local.forEachIndexed { i, l ->
            val o = max(0.0, min(c.end, l.end) - max(c.start, l.start))
            if (o > bestOverlap) { bestOverlap = o; best = i }
        }
        if (bestOverlap > 0) return best
        val mid = (c.start + c.end) / 2
        return local.indices.minByOrNull { abs((local[it].start + local[it].end) / 2 - mid) } ?: 0
    }
}
