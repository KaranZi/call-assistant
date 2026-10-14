package com.karan.callassistant

import kotlin.math.sqrt

/**
 * Works out who said what on a speakerphone call, where both voices land on one mic track.
 *
 * Vosk's speaker model gives each utterance a voiceprint vector. We split the utterances into
 * two groups by similarity (2-means on cosine similarity). If the user has saved their own
 * voiceprint, the group closer to it is "You" and the other "Them"; otherwise they are
 * "Speaker 1" (whoever spoke first) and "Speaker 2". Short utterances with no voiceprint take
 * the label of the utterance before them.
 *
 * The thresholds are first guesses; a few real calls will show whether they need tuning.
 */
object SpeakerLabeler {

    /** Centroids more similar than this are treated as the same person (a one-voice call). */
    private const val SAME_SPEAKER_SIMILARITY = 0.80f

    const val YOU = "You"
    const val THEM = "Them"

    fun label(segments: List<Transcriber.Segment>, me: FloatArray?): List<String> {
        val idx = segments.indices.filter { segments[it].xvector != null }
        val labels = arrayOfNulls<String>(segments.size)

        if (idx.isNotEmpty()) {
            val vecs = idx.map { normalize(segments[it].xvector!!) }
            val (assign, c0, c1) = twoMeans(vecs)
            val twoVoices = c1 != null && cos(c0, c1) < SAME_SPEAKER_SIMILARITY
            val names: Array<String> = when {
                !twoVoices && me != null ->
                    if (cos(c0, normalize(me)) >= 0.5f) arrayOf(YOU, YOU) else arrayOf(THEM, THEM)
                !twoVoices -> arrayOf("Speaker 1", "Speaker 1")
                me != null -> {
                    val m = normalize(me)
                    if (cos(c0, m) >= cos(c1!!, m)) arrayOf(YOU, THEM) else arrayOf(THEM, YOU)
                }
                // Name groups by who spoke first.
                assign[0] == 0 -> arrayOf("Speaker 1", "Speaker 2")
                else -> arrayOf("Speaker 2", "Speaker 1")
            }
            idx.forEachIndexed { k, segIndex -> labels[segIndex] = names[assign[k]] }
        }

        // Fill gaps from the previous label, or the next one at the start.
        var last: String? = labels.firstOrNull { it != null }
        return labels.map { l -> (l ?: last ?: "Speaker").also { last = it } }
    }

    /** Renders "You: …" lines, merging consecutive utterances by the same speaker. */
    fun format(segments: List<Transcriber.Segment>, labels: List<String>): String {
        val sb = StringBuilder()
        var current: String? = null
        segments.forEachIndexed { i, s ->
            if (labels[i] != current) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(labels[i]).append(": ")
                current = labels[i]
            } else {
                sb.append(' ')
            }
            sb.append(s.text)
        }
        return sb.toString()
    }

    fun average(vectors: List<FloatArray>): FloatArray? {
        if (vectors.isEmpty()) return null
        val out = FloatArray(vectors[0].size)
        vectors.forEach { v -> val n = normalize(v); for (i in out.indices) out[i] += n[i] }
        return normalize(out)
    }

    private fun twoMeans(vecs: List<FloatArray>): Triple<IntArray, FloatArray, FloatArray?> {
        val assign = IntArray(vecs.size)
        if (vecs.size < 2) return Triple(assign, vecs[0], null)
        var c0 = vecs[0]
        var c1 = vecs.minByOrNull { cos(it, c0) }!!
        repeat(15) {
            for (i in vecs.indices) assign[i] = if (cos(vecs[i], c0) >= cos(vecs[i], c1)) 0 else 1
            val g0 = vecs.filterIndexed { i, _ -> assign[i] == 0 }
            val g1 = vecs.filterIndexed { i, _ -> assign[i] == 1 }
            if (g1.isEmpty()) return Triple(assign, average(g0)!!, null)
            c0 = average(g0)!!
            c1 = average(g1)!!
        }
        return Triple(assign, c0, c1)
    }

    private fun normalize(v: FloatArray): FloatArray {
        val n = sqrt(v.fold(0f) { a, x -> a + x * x })
        return if (n == 0f) v.copyOf() else FloatArray(v.size) { v[it] / n }
    }

    private fun cos(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
