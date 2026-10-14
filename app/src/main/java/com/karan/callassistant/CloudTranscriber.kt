package com.karan.callassistant

import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Transcription with Whisper on Groq's free tier (needs the same free Groq key as the drafts).
 * Much better than the offline engine on Indian accents, Hindi and Hinglish, but it sends the
 * call audio to Groq. If it fails (no network, rate limit, bad key) the caller falls back to
 * the offline Vosk transcript.
 *
 * The free tier caps uploads at 25 MB, so long calls are sent in 10-minute chunks.
 */
object CloudTranscriber {

    private const val TAG = "CloudTranscriber"
    private const val URL_GROQ = "https://api.groq.com/openai/v1/audio/transcriptions"
    private val MODELS = listOf("whisper-large-v3", "whisper-large-v3-turbo")
    private const val CHUNK_SECONDS = 600
    private const val BYTES_PER_SECOND = AudioRecorder.SAMPLE_RATE * 2

    data class Result(val segments: List<Transcriber.Segment>, val language: String)

    /** [language] is "en", "hi" or null for auto-detect. */
    fun transcribe(apiKey: String, pcm: File, language: String?): Result {
        val chunkBytes = CHUNK_SECONDS.toLong() * BYTES_PER_SECOND
        val segments = mutableListOf<Transcriber.Segment>()
        var detected = ""
        RandomAccessFile(pcm, "r").use { raf ->
            var offset = 0L
            while (offset < raf.length()) {
                val len = minOf(chunkBytes, raf.length() - offset).toInt()
                val data = ByteArray(len)
                raf.seek(offset)
                raf.readFully(data)
                val base = offset.toDouble() / BYTES_PER_SECOND
                val json = postWithFallback(apiKey, wav(data), language)
                if (detected.isEmpty()) detected = json.optString("language")
                val arr = json.optJSONArray("segments")
                if (arr != null && arr.length() > 0) {
                    for (i in 0 until arr.length()) {
                        val seg = arr.getJSONObject(i)
                        val text = seg.optString("text").trim()
                        if (text.isNotEmpty()) {
                            segments += Transcriber.Segment(
                                text, null,
                                base + seg.optDouble("start", 0.0),
                                base + seg.optDouble("end", 0.0)
                            )
                        }
                    }
                } else {
                    json.optString("text").trim().takeIf { it.isNotEmpty() }
                        ?.let { segments += Transcriber.Segment(it, null, base, base + len / BYTES_PER_SECOND) }
                }
                offset += len
            }
        }
        return Result(segments, detected)
    }

    private fun postWithFallback(key: String, wav: ByteArray, language: String?): JSONObject {
        var last: Exception? = null
        for (model in MODELS) {
            try {
                return post(key, wav, model, language)
            } catch (e: ModelGone) {
                Log.w(TAG, "$model unavailable, trying next")
                last = e
            }
        }
        throw last ?: IllegalStateException("no Whisper model available")
    }

    private class ModelGone(msg: String) : Exception(msg)

    private fun post(key: String, wav: ByteArray, model: String, language: String?): JSONObject {
        val boundary = "----callassistant${System.nanoTime()}"
        val body = ByteArrayOutputStream()
        val out = DataOutputStream(body)
        fun field(name: String, value: String) {
            out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
        }
        field("model", model)
        field("response_format", "verbose_json")
        field("temperature", "0")
        if (language != null) field("language", language)
        out.writeBytes(
            "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"call.wav\"\r\n" +
                "Content-Type: audio/wav\r\n\r\n"
        )
        out.write(wav)
        out.writeBytes("\r\n--$boundary--\r\n")
        out.flush()

        val conn = URL(URL_GROQ).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 180_000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setFixedLengthStreamingMode(body.size())
            conn.outputStream.use { body.writeTo(it) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 401 || code == 403) throw IllegalStateException("Groq key rejected (HTTP $code)")
            if ((code == 400 || code == 404) && text.contains("model", ignoreCase = true)) throw ModelGone(text.take(120))
            if (code == 429) throw IllegalStateException("Groq free-tier limit reached")
            if (code !in 200..299) throw IllegalStateException("HTTP $code ${text.take(120)}")
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    /** Wraps raw 16 kHz mono 16-bit PCM in a WAV header. */
    private fun wav(pcm: ByteArray): ByteArray {
        val rate = AudioRecorder.SAMPLE_RATE
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1.toShort()).putShort(1.toShort())
            .putInt(rate).putInt(rate * 2).putShort(2.toShort()).putShort(16.toShort())
        h.put("data".toByteArray()).putInt(pcm.size)
        return h.array() + pcm
    }
}
