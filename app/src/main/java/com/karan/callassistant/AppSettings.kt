package com.karan.callassistant

import android.content.Context

/** Small app-private preferences (backups are disabled in the manifest). */
object AppSettings {

    private const val K_LANGUAGE = "language"
    private const val K_VOICEPRINT = "voiceprint"
    private const val K_ARMED = "armed"

    private fun prefs(c: Context) = c.getSharedPreferences("app", Context.MODE_PRIVATE)

    fun language(c: Context): Transcriber.Language = runCatching {
        Transcriber.Language.valueOf(prefs(c).getString(K_LANGUAGE, null)!!)
    }.getOrDefault(Transcriber.Language.AUTO)

    fun setLanguage(c: Context, lang: Transcriber.Language) =
        prefs(c).edit().putString(K_LANGUAGE, lang.name).apply()

    /** The user's own voiceprint, recorded once from the main screen. */
    fun voiceprint(c: Context): FloatArray? =
        prefs(c).getString(K_VOICEPRINT, null)
            ?.split(',')?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.isNotEmpty() }?.toFloatArray()

    fun setVoiceprint(c: Context, v: FloatArray?) =
        prefs(c).edit().putString(K_VOICEPRINT, v?.joinToString(",")).apply()

    /** Whether the user left the monitor armed; used to re-arm after reboot or update. */
    fun armed(c: Context): Boolean = prefs(c).getBoolean(K_ARMED, false)

    fun setArmed(c: Context, armed: Boolean) =
        prefs(c).edit().putBoolean(K_ARMED, armed).apply()
}
