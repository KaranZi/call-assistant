package com.karan.callassistant

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * On-device, offline transcription with Vosk (free, open source, no key, audio never leaves
 * the phone).
 *
 * Models ship inside the APK under `assets/` (CI downloads them before the build; for a local
 * build unzip them yourself, see README):
 *  - `vosk-model-en`  small Indian English model
 *  - `vosk-model-hi`  small Hindi model
 *  - `vosk-model-spk` speaker-ID model, used to tell voices apart on speakerphone calls
 *
 * On first use each model is copied out of the APK into app storage, because Vosk loads models
 * from a real directory. That takes a few seconds once per app version.
 */
object Transcriber {

    private const val TAG = "Transcriber"
    private const val SPK_DIR = "vosk-model-spk"

    /** Seconds of audio used to decide between English and Hindi in Auto mode. */
    private const val LANG_PROBE_SECONDS = 45

    enum class Language(val label: String, val assetDir: String) {
        AUTO("Auto (English or Hindi)", ""),
        ENGLISH("English (Indian accent)", "vosk-model-en"),
        HINDI("Hindi", "vosk-model-hi")
    }

    /**
     * One recognised utterance plus its voiceprint (null when too short to fingerprint) and its
     * time span in seconds (used to line up cloud transcripts with offline speaker labels).
     */
    data class Segment(
        val text: String,
        val xvector: FloatArray?,
        val start: Double = 0.0,
        val end: Double = 0.0
    )

    data class Output(val segments: List<Segment>, val language: Language) {
        val plainText: String get() = segments.joinToString(" ") { it.text }.trim()
    }

    private val models = mutableMapOf<String, Model>()
    private var speakerModel: SpeakerModel? = null

    // ---- public API ----------------------------------------------------------

    /**
     * Transcribes 16 kHz mono PCM. Returns null when no speech model is bundled.
     * With [withVoiceprints], each segment also carries a speaker vector for [SpeakerLabeler].
     */
    fun transcribe(
        context: Context,
        audioFile: File,
        withVoiceprints: Boolean,
        forceLanguage: Language? = null
    ): Output? {
        val lang = forceLanguage?.takeIf { hasModel(context, it) }
            ?: chooseLanguage(context, audioFile) ?: return null
        val model = loadModel(context, lang.assetDir) ?: return null
        val spk = if (withVoiceprints) loadSpeakerModel(context) else null
        val segments = FileInputStream(audioFile).use { recognize(model, spk, it, Long.MAX_VALUE).first }
        return Output(segments, lang)
    }

    /**
     * Averages the voiceprints in a recording of the user speaking alone. Saved and later used
     * to work out which voice on a speakerphone call is "You".
     */
    fun voiceprint(context: Context, audioFile: File): FloatArray? {
        val spk = loadSpeakerModel(context) ?: return null
        val lang = AppSettings.language(context).takeIf { it != Language.AUTO } ?: Language.ENGLISH
        val model = loadModel(context, lang.assetDir)
            ?: loadModel(context, Language.ENGLISH.assetDir) ?: return null
        val segments = FileInputStream(audioFile).use { recognize(model, spk, it, Long.MAX_VALUE).first }
        return SpeakerLabeler.average(segments.mapNotNull { it.xvector })
    }

    fun hasModel(context: Context, lang: Language): Boolean =
        context.assets.list(lang.assetDir).orEmpty().isNotEmpty()

    // ---- language choice -----------------------------------------------------

    private fun chooseLanguage(context: Context, audioFile: File): Language? {
        val en = hasModel(context, Language.ENGLISH)
        val hi = hasModel(context, Language.HINDI)
        val wanted = AppSettings.language(context)
        when {
            !en && !hi -> return null
            wanted == Language.ENGLISH && en -> return Language.ENGLISH
            wanted == Language.HINDI && hi -> return Language.HINDI
            !hi -> return Language.ENGLISH
            !en -> return Language.HINDI
        }
        // Auto: run the first ~45 s through both models and keep whichever is more confident.
        val limit = LANG_PROBE_SECONDS.toLong() * AudioRecorder.SAMPLE_RATE * 2
        val enConf = probeConfidence(context, Language.ENGLISH, audioFile, limit)
        val hiConf = probeConfidence(context, Language.HINDI, audioFile, limit)
        Log.i(TAG, "Auto language: en=$enConf hi=$hiConf")
        return if (hiConf > enConf) Language.HINDI else Language.ENGLISH
    }

    private fun probeConfidence(context: Context, lang: Language, file: File, limit: Long): Double {
        val model = loadModel(context, lang.assetDir) ?: return 0.0
        return FileInputStream(file).use { recognize(model, null, it, limit).second }
    }

    // ---- recognition ---------------------------------------------------------

    /** Returns the segments and the mean per-word confidence. */
    private fun recognize(
        model: Model,
        spk: SpeakerModel?,
        input: InputStream,
        maxBytes: Long
    ): Pair<List<Segment>, Double> {
        val recognizer = Recognizer(model, AudioRecorder.SAMPLE_RATE.toFloat())
        recognizer.setWords(true)
        if (spk != null) recognizer.setSpeakerModel(spk)

        val segments = mutableListOf<Segment>()
        var confSum = 0.0
        var words = 0
        fun collect(json: String) {
            val o = JSONObject(json)
            val text = o.optString("text").trim()
            if (text.isEmpty()) return
            var start = 0.0
            var end = 0.0
            o.optJSONArray("result")?.let { arr ->
                for (i in 0 until arr.length()) {
                    confSum += arr.getJSONObject(i).optDouble("conf", 0.0)
                    words++
                }
                if (arr.length() > 0) {
                    start = arr.getJSONObject(0).optDouble("start", 0.0)
                    end = arr.getJSONObject(arr.length() - 1).optDouble("end", start)
                }
            }
            val vec = o.optJSONArray("spk")?.let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
            segments += Segment(text, vec, start, end)
        }

        try {
            val buf = ByteArray(8192)
            var read = 0L
            while (read < maxBytes) {
                val n = input.read(buf)
                if (n <= 0) break
                read += n
                if (recognizer.acceptWaveForm(buf, n)) collect(recognizer.result)
            }
            collect(recognizer.finalResult)
        } finally {
            recognizer.close()
        }
        return segments to (if (words == 0) 0.0 else confSum / words)
    }

    // ---- model loading -------------------------------------------------------

    @Synchronized
    private fun loadModel(context: Context, assetDir: String): Model? {
        models[assetDir]?.let { return it }
        val dir = unpack(context, assetDir) ?: return null
        return try {
            Model(dir.absolutePath).also { models[assetDir] = it }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $assetDir", e)
            null
        }
    }

    @Synchronized
    private fun loadSpeakerModel(context: Context): SpeakerModel? {
        speakerModel?.let { return it }
        val dir = unpack(context, SPK_DIR) ?: return null
        return try {
            SpeakerModel(dir.absolutePath).also { speakerModel = it }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load speaker model", e)
            null
        }
    }

    /** Copies an asset model folder to app storage once per app version. */
    private fun unpack(context: Context, assetDir: String): File? {
        if (context.assets.list(assetDir).isNullOrEmpty()) return null
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        val version = context.packageManager
            .getPackageInfo(context.packageName, 0).longVersionCode
        val target = File(context.filesDir, "$assetDir-v$version")
        val marker = File(target, ".complete")
        if (!marker.exists()) {
            // Drop copies left by older app versions (they can be tens of MB each).
            context.filesDir.listFiles()
                ?.filter { it.name.startsWith("vosk-model") && !it.name.endsWith("-v$version") }
                ?.forEach { it.deleteRecursively() }
            target.deleteRecursively()
            copyAssetDir(context, assetDir, target)
            marker.createNewFile()
        }
        return target
    }

    private fun copyAssetDir(context: Context, assetPath: String, dest: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            return
        }
        dest.mkdirs()
        children.forEach { copyAssetDir(context, "$assetPath/$it", File(dest, it)) }
    }
}
