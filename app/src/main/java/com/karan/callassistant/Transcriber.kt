package com.karan.callassistant

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileInputStream

/**
 * On-device, offline transcription with Vosk (free, open source, no key, audio never leaves
 * the phone).
 *
 * The speech model ships inside the APK under `assets/vosk-model/`. CI downloads it before the
 * build (see .github/workflows/build.yml); for a local build, unzip a model from
 * https://alphacephei.com/vosk/models into that folder yourself. Default is the small Indian
 * English model (vosk-model-small-en-in-0.4, ~36 MB).
 *
 * On first use the model is copied out of the APK into app storage, because Vosk loads it from
 * a real directory. That takes a few seconds once; later calls reuse the copy.
 */
object Transcriber {

    private const val TAG = "Transcriber"
    private const val ASSET_DIR = "vosk-model"

    @Volatile private var model: Model? = null

    fun transcribe(context: Context, audioFile: File?): String {
        if (audioFile == null || audioFile.length() == 0L) {
            return "(No audio captured. Make sure the mic permission is granted and, for " +
                "both-sides capture, that the call was on speakerphone.)"
        }
        val m = try {
            loadModel(context)
        } catch (e: Exception) {
            Log.e(TAG, "Model load failed", e)
            null
        } ?: return "(Speech model missing from this build, so no transcript.)"

        val recognizer = Recognizer(m, AudioRecorder.SAMPLE_RATE.toFloat())
        val text = StringBuilder()
        try {
            FileInputStream(audioFile).use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    if (recognizer.acceptWaveForm(buf, n)) appendText(text, recognizer.result)
                }
            }
            appendText(text, recognizer.finalResult)
        } finally {
            recognizer.close()
        }
        return text.toString().trim().ifEmpty { "(No speech recognised in the recording.)" }
    }

    /** True once the bundled model can be loaded; also warms it up so the first call is fast. */
    fun isReady(context: Context): Boolean =
        try { loadModel(context) != null } catch (e: Exception) { false }

    private fun appendText(sb: StringBuilder, json: String) {
        val t = JSONObject(json).optString("text")
        if (t.isNotBlank()) sb.append(t).append(' ')
    }

    @Synchronized
    private fun loadModel(context: Context): Model? {
        model?.let { return it }
        if (context.assets.list(ASSET_DIR).isNullOrEmpty()) return null
        LibVosk.setLogLevel(LogLevel.WARNINGS)

        // Re-copy whenever the app is updated, in case the bundled model changed.
        val version = context.packageManager
            .getPackageInfo(context.packageName, 0).longVersionCode
        val target = File(context.filesDir, "$ASSET_DIR-$version")
        val marker = File(target, ".complete")
        if (!marker.exists()) {
            context.filesDir.listFiles { f -> f.name.startsWith("$ASSET_DIR-") }
                ?.forEach { it.deleteRecursively() }
            copyAssetDir(context, ASSET_DIR, target)
            marker.createNewFile()
        }
        return Model(target.absolutePath).also { model = it }
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
