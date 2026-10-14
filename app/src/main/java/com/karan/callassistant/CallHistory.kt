package com.karan.callassistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Keeps every processed call (transcript + drafts) as a small JSON file in app storage, so a
 * pop-up you closed can be found again from the Call history screen. Audio is never stored.
 */
object CallHistory {

    data class Record(
        val id: Long,               // call end time, epoch millis
        val number: String,
        val contactLabel: String,
        val captureNote: String,    // e.g. "speakerphone · Hindi · Groq"
        val transcript: String,
        val drafts: List<DraftGenerator.Draft>
    )

    private fun dir(c: Context) = File(c.filesDir, "history").apply { mkdirs() }

    fun save(c: Context, r: Record) {
        val drafts = JSONArray()
        r.drafts.forEach { drafts.put(JSONObject().put("title", it.title).put("body", it.body)) }
        val json = JSONObject()
            .put("id", r.id)
            .put("number", r.number)
            .put("contactLabel", r.contactLabel)
            .put("captureNote", r.captureNote)
            .put("transcript", r.transcript)
            .put("drafts", drafts)
        File(dir(c), "${r.id}.json").writeText(json.toString())
    }

    /** Newest first. */
    fun list(c: Context): List<Record> =
        dir(c).listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { runCatching { parse(it.readText()) }.getOrNull() }
            .sortedByDescending { it.id }

    fun get(c: Context, id: Long): Record? =
        File(dir(c), "$id.json").takeIf { it.exists() }?.let { runCatching { parse(it.readText()) }.getOrNull() }

    fun delete(c: Context, id: Long) {
        File(dir(c), "$id.json").delete()
    }

    private fun parse(text: String): Record {
        val o = JSONObject(text)
        val arr = o.optJSONArray("drafts") ?: JSONArray()
        val drafts = (0 until arr.length()).map {
            val d = arr.getJSONObject(it)
            DraftGenerator.Draft(d.optString("title"), d.optString("body"))
        }
        return Record(
            o.getLong("id"),
            o.optString("number"),
            o.optString("contactLabel"),
            o.optString("captureNote"),
            o.optString("transcript"),
            drafts
        )
    }
}
