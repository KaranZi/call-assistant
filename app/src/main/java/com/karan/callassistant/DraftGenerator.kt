package com.karan.callassistant

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Turns a call transcript into a few suggested follow-up messages.
 *
 * Two routes, both free:
 *  - **Offline** (default, works with no setup): rule-based drafts that pull times, days and
 *    action phrases out of the transcript. Nothing leaves the phone.
 *  - **Free-tier LLM** over an OpenAI-compatible chat API (Groq, Google Gemini, or any custom
 *    endpoint such as OpenRouter or a local Ollama). Needs a free key, no payment. This sends
 *    the transcript *text* to that provider; audio still never leaves the phone.
 *
 * If the LLM call fails for any reason (no network, bad key, rate limit) we fall back to the
 * offline drafts, so the pop-up always has something to show.
 */
object DraftGenerator {

    private const val TAG = "DraftGenerator"

    /** A follow-up suggestion the user can send as-is or edit. */
    data class Draft(val title: String, val body: String)

    /** Drafts plus a short note on where they came from, shown in the pop-up. */
    data class Result(val drafts: List<Draft>, val source: String)

    fun generate(context: Context, transcript: String, recordingNote: String = ""): Result {
        val settings = LlmSettings.load(context)
        val needsKey = settings.provider != LlmSettings.Provider.CUSTOM
        if (settings.provider == LlmSettings.Provider.OFFLINE || (needsKey && settings.apiKey.isBlank())) {
            return Result(offline(transcript), "offline drafts")
        }
        return try {
            try {
                Result(llm(settings, transcript, recordingNote), settings.provider.label)
            } catch (e: ModelUnavailable) {
                // Providers retire models; pick one the key can use now and remember it.
                val replacement = pickAvailableModel(settings) ?: throw e
                Log.i(TAG, "Model ${settings.model} unavailable; switching to $replacement")
                val updated = settings.copy(model = replacement)
                updated.save(context)
                Result(llm(updated, transcript, recordingNote), "${settings.provider.label}, $replacement")
            }
        } catch (e: Exception) {
            Log.w(TAG, "LLM drafts failed; using offline", e)
            Result(offline(transcript), "offline drafts (${settings.provider.label} failed: ${e.message?.take(80)})")
        }
    }

    fun sampleDrafts(): List<Draft> = offline("")

    // ---- free-tier LLM -------------------------------------------------------

    private fun llm(s: LlmSettings, transcript: String, recordingNote: String): List<Draft> {
        val prompt = """
            Below is an automatic transcript of a phone call I (the user) just finished. It may
            contain recognition errors. $recordingNote
            Write 3 short follow-up messages I could send to the other person on WhatsApp: one
            recap of what was agreed, one about the next action or time, one brief thanks.
            Write in first person, plain and friendly, under 50 words each. Match the call's
            language: if it was in Hindi or Hinglish, write Hinglish in Roman script. Don't
            invent facts that aren't in the transcript.
            Reply with ONLY a JSON array like [{"title":"Recap","body":"..."}].

            Transcript:
            ${transcript.take(6000)}
        """.trimIndent()

        val body = JSONObject()
            .put("model", s.model)
            .put("temperature", 0.4)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))

        val conn = URL(s.endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 45_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (s.apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${s.apiKey}")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 401 || code == 403) throw IllegalStateException("key rejected (HTTP $code)")
            if ((code == 400 || code == 404) && text.contains("model", ignoreCase = true)) {
                throw ModelUnavailable("model ${s.model} not available")
            }
            if (code == 429) throw IllegalStateException("free-tier rate limit hit, try again later")
            if (code !in 200..299) throw IllegalStateException("HTTP $code ${text.take(120)}")

            val content = JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
            return parseDrafts(content)
        } finally {
            conn.disconnect()
        }
    }

    private class ModelUnavailable(msg: String) : Exception(msg)

    /** Lists the provider's models (OpenAI-style GET /models) and picks a general chat model. */
    private fun pickAvailableModel(s: LlmSettings): String? {
        val url = s.endpoint.substringBeforeLast("/chat/completions", "")
        if (url.isEmpty()) return null
        val conn = URL("$url/models").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            if (s.apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${s.apiKey}")
            if (conn.responseCode !in 200..299) return null
            val data = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                .optJSONArray("data") ?: return null
            val ids = (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id") }
                .filter { id ->
                    val l = id.lowercase()
                    listOf("whisper", "tts", "guard", "embed", "vision", "audio", "image")
                        .none { it in l } && id != s.model
                }
            val prefs = listOf(
                { id: String -> "gpt-oss-20b" in id }, { id: String -> "gpt-oss" in id },
                { id: String -> "flash" in id }, { id: String -> "llama" in id },
                { id: String -> "qwen" in id }, { _: String -> true }
            )
            prefs.firstNotNullOfOrNull { p -> ids.firstOrNull { p(it.lowercase()) } }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Models sometimes wrap the JSON in prose or code fences; take the outermost array. */
    private fun parseDrafts(content: String): List<Draft> {
        val start = content.indexOf('[')
        val end = content.lastIndexOf(']')
        require(start >= 0 && end > start) { "no JSON array in reply" }
        val arr = JSONArray(content.substring(start, end + 1))
        val drafts = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val b = o.optString("body").trim()
            if (b.isEmpty()) null else Draft(o.optString("title", "Suggestion").trim(), b)
        }
        require(drafts.isNotEmpty()) { "empty draft list" }
        return drafts.take(4)
    }

    // ---- offline drafts ------------------------------------------------------

    private val timeRegex = Regex(
        "\\b(today|tonight|tomorrow|day after tomorrow|this (morning|afternoon|evening|week|weekend)|" +
            "next (week|month|monday|tuesday|wednesday|thursday|friday|saturday|sunday)|" +
            "(on )?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)|" +
            "\\d{1,2}(:\\d{2})? ?(am|pm|o'clock)|(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve) (o'clock|am|pm))\\b",
        RegexOption.IGNORE_CASE
    )

    private val actionRegex = Regex(
        "\\b(i will|i'll|we will|we'll|you will|you'll|please|let me|can you|could you|need to|have to)\\b[^.?!]{3,80}",
        RegexOption.IGNORE_CASE
    )

    private fun offline(transcript: String): List<Draft> {
        val clean = if (transcript.startsWith("(")) "" else transcript
        val time = timeRegex.find(clean)?.value?.lowercase()
        val action = actionRegex.find(clean)?.value?.trim()?.trimEnd(',', ' ')

        val recap = if (action != null) {
            "Thanks for the call. Just confirming: $action" + (if (time != null) " ($time)." else ".")
        } else {
            "Thanks for the call. To recap what we agreed: "
        }
        val next = when {
            time != null -> "As discussed, talk/meet $time. Let me know if that timing changes."
            else -> "Following up on our call. I'll "
        }
        return listOf(
            Draft("Quick recap", recap),
            Draft("Next step", next),
            Draft("Thanks", "Great talking just now, thanks for your time!")
        )
    }
}

/** Where drafts come from. Stored in app-private SharedPreferences (backups are disabled). */
data class LlmSettings(
    val provider: Provider,
    val apiKey: String,
    val model: String,
    val customUrl: String
) {
    enum class Provider(
        val label: String,
        val url: String,
        val defaultModel: String,
        val keyPage: String
    ) {
        OFFLINE("Offline (no key)", "", "", ""),
        GROQ(
            "Groq (free key)",
            "https://api.groq.com/openai/v1/chat/completions",
            "openai/gpt-oss-20b",
            "https://console.groq.com/keys"
        ),
        GEMINI(
            "Google Gemini (free key)",
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            "gemini-flash-latest",
            "https://aistudio.google.com/apikey"
        ),
        CUSTOM("Custom OpenAI-compatible URL", "", "", "")
    }

    val endpoint: String get() = if (provider == Provider.CUSTOM) customUrl else provider.url

    fun save(context: Context) {
        prefs(context).edit()
            .putString(K_PROVIDER, provider.name)
            .putString(K_KEY, apiKey)
            .putString(K_MODEL, model)
            .putString(K_URL, customUrl)
            .apply()
    }

    companion object {
        private const val K_PROVIDER = "provider"
        private const val K_KEY = "api_key"
        private const val K_MODEL = "model"
        private const val K_URL = "custom_url"

        private fun prefs(c: Context) = c.getSharedPreferences("llm", Context.MODE_PRIVATE)

        fun load(context: Context): LlmSettings {
            val p = prefs(context)
            val provider = runCatching {
                Provider.valueOf(p.getString(K_PROVIDER, Provider.OFFLINE.name)!!)
            }.getOrDefault(Provider.OFFLINE)
            val model = p.getString(K_MODEL, "").orEmpty().ifBlank { provider.defaultModel }
            return LlmSettings(
                provider,
                p.getString(K_KEY, "").orEmpty().trim(),
                model,
                p.getString(K_URL, "").orEmpty().trim()
            )
        }
    }
}
