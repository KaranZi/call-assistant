package com.karan.callassistant

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.concurrent.Executors

/**
 * Foreground service that is the heart of the spike. While "armed" it watches the phone's
 * call state. When a call goes off-hook it starts [AudioRecorder]; when the call returns to
 * idle it stops recording, kicks off transcription + draft generation, and shows the
 * post-call overlay via [OverlayManager].
 *
 * Call-state detection uses [TelephonyCallback] on API 31+ and the deprecated
 * [PhoneStateListener] below that, which is the documented path for older devices.
 */
class CallMonitorService : Service() {

    private lateinit var telephonyManager: TelephonyManager
    private var armed = false

    private var recorder: AudioRecorder? = null
    private var activeCallNumber: String? = null
    private var callActive = false
    private var captureSourceLabel = ""

    private val worker = Executors.newSingleThreadExecutor()

    // API 31+ listener
    private var telephonyCallback: TelephonyCallback? = null
    // < API 31 listener
    private var legacyListener: PhoneStateListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ARM -> arm()
            ACTION_DISARM -> {
                disarm()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun arm() {
        if (armed) return
        startAsForeground()
        registerCallStateListener()
        armed = true
        Log.i(TAG, "Armed: watching call state")
    }

    private fun disarm() {
        if (!armed) return
        unregisterCallStateListener()
        if (callActive) onCallEnded()
        armed = false
        Log.i(TAG, "Disarmed")
    }

    // ---- call-state plumbing -------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun registerCallStateListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = handleState(state)
            }
            telephonyCallback = cb
            telephonyManager.registerTelephonyCallback(mainExecutor, cb)
        } else {
            @Suppress("DEPRECATION")
            val l = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    if (phoneNumber != null) activeCallNumber = phoneNumber
                    handleState(state)
                }
            }
            legacyListener = l
            @Suppress("DEPRECATION")
            telephonyManager.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    private fun unregisterCallStateListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { telephonyManager.unregisterTelephonyCallback(it) }
            telephonyCallback = null
        } else {
            @Suppress("DEPRECATION")
            legacyListener?.let { telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE) }
            legacyListener = null
        }
    }

    private fun handleState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> if (!callActive) onCallStarted()
            TelephonyManager.CALL_STATE_IDLE -> if (callActive) onCallEnded()
            // RINGING: we learn the number here on older APIs; nothing to record yet.
        }
    }

    // ---- lifecycle of a single call -----------------------------------------

    private fun onCallStarted() {
        callActive = true
        // Best-effort: resolve the most recent call-log number if we didn't get it from the listener.
        if (activeCallNumber == null) {
            activeCallNumber = ContactHelper.latestCallLogNumber(this)
        }
        // Pick the best capture source this device actually allows: whole-call / downlink if the
        // device-level route works here, otherwise the speakerphone mic fallback.
        val source = DeviceProbe.preferredSource(this)
        captureSourceLabel = DeviceProbe.sourceLabel(source)
        val outFile = File(cacheDir, "call_${System.currentTimeMillis()}.pcm")
        recorder = AudioRecorder(outFile, source).also { it.start() }
        Log.i(TAG, "Call started; source=$captureSourceLabel; recording to ${outFile.name}")
    }

    private fun onCallEnded() {
        callActive = false
        val rec = recorder
        recorder = null
        val number = activeCallNumber
        activeCallNumber = null

        val peak = rec?.peakAmplitude ?: 0
        val audioFile = rec?.stop()
        val label = captureSourceLabel
        Log.i(TAG, "Call ended; audio=${audioFile?.name}; source=$label; peak=$peak")

        worker.execute { processCall(audioFile, number, label, peak) }
    }

    private fun processCall(audioFile: File?, number: String?, sourceLabel: String, peak: Int) {
        // 1. Transcribe on-device with Vosk (offline, free; audio stays on the phone).
        val transcript = try {
            Transcriber.transcribe(this, audioFile)
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            "(Transcription failed: ${e.message})"
        }
        // The transcript is all we need; don't keep call audio lying around.
        audioFile?.delete()

        // 2. Drafts: free-tier LLM if a key is set in the app, else offline rule-based drafts.
        val result = DraftGenerator.generate(this, transcript)
        val drafts = result.drafts

        // 3. Resolve the contact label, and offer to save the number if unknown.
        val label = ContactHelper.labelForNumber(this, number)
        if (number != null && !ContactHelper.isKnown(this, number)) {
            ContactHelper.maybeSaveUnknown(this, number)
        }

        // Capture diagnostic: proves on a real call whether the chosen source actually
        // picked up sound (peak > ~500 ≈ real audio; ~0 = silent source, so it fell through).
        val captureLine = "[capture: $sourceLabel · peak $peak" +
            (if (peak < 500) " → looks SILENT, likely needs speakerphone" else "") +
            " · ${result.source}]"

        // 4. Show the post-call overlay on the main thread.
        mainExecutor.execute {
            OverlayManager(applicationContext).show(
                contactLabel = label,
                number = number ?: "Unknown",
                transcriptPreview = "$captureLine\n$transcript",
                drafts = drafts
            )
        }
    }

    // ---- foreground notification --------------------------------------------

    private fun startAsForeground() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Call monitoring",
                NotificationManager.IMPORTANCE_LOW
            )
            mgr.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Call Assistant armed")
            .setContentText("Watching for calls")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        disarm()
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_ARM = "com.karan.callassistant.ARM"
        const val ACTION_DISARM = "com.karan.callassistant.DISARM"
        private const val CHANNEL_ID = "call_monitor"
        private const val NOTIF_ID = 42
        private const val TAG = "CallMonitor"
    }
}
