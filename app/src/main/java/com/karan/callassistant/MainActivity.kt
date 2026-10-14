package com.karan.callassistant

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

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
        ContextCompat.startForegroundService(
            this,
            Intent(this, CallMonitorService::class.java).setAction(CallMonitorService.ACTION_ARM)
        )
        refreshStatus()
    }

    private fun disarmService() {
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

    private fun hasAllRuntimePermissions(): Boolean =
        runtimePermissions().all {
            ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    private fun refreshStatus() {
        val overlay = if (Settings.canDrawOverlays(this)) "granted" else "MISSING"
        val perms = if (hasAllRuntimePermissions()) "granted" else "MISSING"
        statusText.text = getString(R.string.status_fmt, perms, overlay)
    }
}
