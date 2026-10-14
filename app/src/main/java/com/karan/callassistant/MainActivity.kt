package com.karan.callassistant

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.concurrent.thread

/**
 * Entry screen. Its only jobs are:
 *  - walk the user through the runtime permissions the spike needs,
 *  - let them arm/disarm the [CallMonitorService] that does the real work,
 *  - let them fire a demo pop-up so the overlay can be verified without a live call.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)

        findViewById<Button>(R.id.grantButton).setOnClickListener { requestRuntimePermissions() }
        findViewById<Button>(R.id.overlayButton).setOnClickListener { requestOverlayPermission() }
        findViewById<Button>(R.id.armButton).setOnClickListener { armService() }
        findViewById<Button>(R.id.disarmButton).setOnClickListener { disarmService() }
        findViewById<Button>(R.id.demoButton).setOnClickListener { demoPopup() }
        findViewById<Button>(R.id.probeButton).setOnClickListener { runDeviceCheck() }
        findViewById<Button>(R.id.testTranscribeButton).setOnClickListener { testTranscription() }
        findViewById<Button>(R.id.voiceprintButton).setOnClickListener { recordVoiceprint() }
        findViewById<Button>(R.id.batteryButton).setOnClickListener { requestBatteryExemption() }
        findViewById<Button>(R.id.historyButton).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        setUpLanguagePicker()
        setUpDraftSettings()
        if (intent?.action == ACTION_REARM) armService()
    }

    private fun setUpLanguagePicker() {
        val spinner = findViewById<Spinner>(R.id.languageSpinner)
        val langs = Transcriber.Language.values()
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, langs.map { it.label }
        )
        spinner.setSelection(langs.indexOf(AppSettings.language(this)))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) =
                AppSettings.setLanguage(this@MainActivity, langs[pos])

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** Lets Samsung's battery saver leave the monitor alone while the screen is off. */
    @android.annotation.SuppressLint("BatteryLife") // personal sideloaded app, not Play Store
    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Already exempt from battery saver", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        )
    }

    /** Records 15 s of the user talking alone and saves their voiceprint for speaker labels. */
    private fun recordVoiceprint() {
        val out = findViewById<TextView>(R.id.voiceprintText)
        if (!hasMicPermission()) {
            out.text = "Grant permissions first."
            return
        }
        val button = findViewById<Button>(R.id.voiceprintButton)
        button.isEnabled = false
        out.text = "Recording… keep talking normally for 15 seconds (read anything aloud)."
        val file = File(cacheDir, "voice_${System.currentTimeMillis()}.pcm")
        val rec = AudioRecorder(file).also { it.start() }
        Handler(Looper.getMainLooper()).postDelayed({
            val audio = rec.stop()
            out.text = "Analysing your voice…"
            thread {
                val vp = try {
                    audio?.let { Transcriber.voiceprint(this, it) }
                } catch (e: Exception) {
                    null
                }
                audio?.delete()
                if (vp != null) AppSettings.setVoiceprint(this, vp)
                runOnUiThread {
                    out.text = if (vp != null) "Voice saved. Speakerphone calls will mark your lines as \"You\"."
                    else "Couldn't get a clear voiceprint. Try again somewhere quiet and talk the whole time."
                    button.isEnabled = true
                }
            }
        }, 15000)
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    // ---- transcription self-test ---------------------------------------------

    /** Records 5 s from the mic and runs it through the same offline engine calls use. */
    private fun testTranscription() {
        val out = findViewById<TextView>(R.id.transcribeText)
        if (!hasMicPermission()) {
            out.text = "Grant permissions first."
            return
        }
        val button = findViewById<Button>(R.id.testTranscribeButton)
        button.isEnabled = false
        out.text = "Listening… speak now."
        val file = File(cacheDir, "test_${System.currentTimeMillis()}.pcm")
        val rec = AudioRecorder(file).also { it.start() }
        Handler(Looper.getMainLooper()).postDelayed({
            val audio = rec.stop()
            out.text = "Transcribing… (the first run unpacks the speech model, a few seconds)"
            thread {
                val text = try {
                    val result = audio?.let { Transcriber.transcribe(this, it, withVoiceprints = false) }
                    when {
                        audio == null -> "(no audio captured)"
                        result == null -> "(speech model missing from this build)"
                        result.segments.isEmpty() -> "(nothing recognised; ${result.language.label})"
                        else -> "${result.plainText}\n[${result.language.label}]"
                    }
                } catch (e: Exception) {
                    "Failed: ${e.message}"
                }
                audio?.delete()
                runOnUiThread {
                    out.text = "Heard: $text"
                    button.isEnabled = true
                }
            }
        }, 5000)
    }

    // ---- draft provider settings ---------------------------------------------

    private fun setUpDraftSettings() {
        val spinner = findViewById<Spinner>(R.id.providerSpinner)
        val keyInput = findViewById<EditText>(R.id.apiKeyInput)
        val modelInput = findViewById<EditText>(R.id.modelInput)
        val urlInput = findViewById<EditText>(R.id.urlInput)
        val providers = LlmSettings.Provider.values()

        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, providers.map { it.label }
        )
        val current = LlmSettings.load(this)
        spinner.setSelection(providers.indexOf(current.provider))
        keyInput.setText(current.apiKey)
        modelInput.setText(if (current.model == current.provider.defaultModel) "" else current.model)
        urlInput.setText(current.customUrl)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val p = providers[pos]
                val online = p != LlmSettings.Provider.OFFLINE
                keyInput.visibility = if (online) View.VISIBLE else View.GONE
                modelInput.visibility = if (online) View.VISIBLE else View.GONE
                modelInput.hint = if (p.defaultModel.isNotEmpty()) "Model (default ${p.defaultModel})"
                else getString(R.string.model_hint)
                urlInput.visibility =
                    if (p == LlmSettings.Provider.CUSTOM) View.VISIBLE else View.GONE
                findViewById<Button>(R.id.getKeyButton).visibility =
                    if (p.keyPage.isNotEmpty()) View.VISIBLE else View.GONE
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.getKeyButton).setOnClickListener {
            val page = providers[spinner.selectedItemPosition].keyPage
            if (page.isNotEmpty()) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page)))
        }

        findViewById<Button>(R.id.saveDraftsButton).setOnClickListener {
            val p = providers[spinner.selectedItemPosition]
            val settings = LlmSettings(
                p,
                keyInput.text.toString().trim(),
                modelInput.text.toString().trim().ifBlank { p.defaultModel },
                urlInput.text.toString().trim()
            )
            settings.save(this)
            val out = findViewById<TextView>(R.id.draftsTestText)
            out.text = "Saved. Testing with a sample transcript…"
            thread {
                val result = DraftGenerator.generate(this, SAMPLE_TRANSCRIPT)
                val text = buildString {
                    append("Source: ").append(result.source).append("\n\n")
                    result.drafts.forEach { append("• ").append(it.title).append(": ").append(it.body).append("\n") }
                }
                runOnUiThread { out.text = text }
            }
        }
    }

    /**
     * Reports what we can't see from anywhere but this phone: its make/model/Android version,
     * and which call-audio sources actually initialize here. This is how we find out whether
     * device-level both-sides capture is even possible on Karan's device.
     */
    private fun runDeviceCheck() {
        val sb = StringBuilder()
        sb.append(DeviceProbe.deviceSummary()).append("\n\nAudio sources that initialize:\n")
        DeviceProbe.probeSources(this).forEach {
            sb.append(if (it.initialized) "  ✓ " else "  ✗ ").append(it.name).append('\n')
        }
        sb.append("\nNote: a source initializing doesn't guarantee it captures the other party — ")
        sb.append("make one test call to confirm (the pop-up reports the captured signal level).")
        findViewById<TextView>(R.id.probeText).text = sb.toString()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun runtimePermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        return perms.toTypedArray()
    }

    private fun requestRuntimePermissions() {
        requestPermissions.launch(runtimePermissions())
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } else {
            Toast.makeText(this, "Overlay already allowed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun armService() {
        if (!hasAllRuntimePermissions()) {
            Toast.makeText(this, "Grant permissions first", Toast.LENGTH_SHORT).show()
            return
        }
        AppSettings.setArmed(this, true)
        ContextCompat.startForegroundService(
            this,
            Intent(this, CallMonitorService::class.java).setAction(CallMonitorService.ACTION_ARM)
        )
        refreshStatus()
    }

    private fun disarmService() {
        AppSettings.setArmed(this, false)
        startService(
            Intent(this, CallMonitorService::class.java).setAction(CallMonitorService.ACTION_DISARM)
        )
        refreshStatus()
    }

    /** Verify the overlay end-to-end without needing a real call. */
    private fun demoPopup() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow 'display over other apps' first", Toast.LENGTH_SHORT).show()
            return
        }
        OverlayManager(applicationContext).show(
            contactLabel = "Demo contact",
            number = "+10000000000",
            transcriptPreview = "This is a sample transcript so you can see the pop-up.",
            drafts = DraftGenerator.sampleDrafts()
        )
    }

    companion object {
        const val ACTION_REARM = "com.karan.callassistant.REARM"
        private const val SAMPLE_TRANSCRIPT =
            "yes so i'll send you the quotation by tonight and we can meet on friday at 4 pm " +
                "to finalise the order please bring the samples"
    }

    private fun hasAllRuntimePermissions(): Boolean =
        runtimePermissions().all {
            ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    private fun refreshStatus() {
        val overlay = if (Settings.canDrawOverlays(this)) "granted" else "MISSING"
        val perms = if (hasAllRuntimePermissions()) "granted" else "MISSING"
        val battery = if (getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(packageName)
        ) "exempt" else "MISSING"
        val armed = if (AppSettings.armed(this)) "ON" else "off"
        statusText.text = getString(R.string.status_fmt, perms, overlay, battery, armed)
    }
}
