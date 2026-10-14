package com.karan.callassistant

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

    // Speakerphone tracking: sampled every couple of seconds while a call is active.
    private val handler = Handler(Looper.getMainLooper())
    private var routeSamples = 0
    private var speakerSamples = 0
    private var headsetSamples = 0
    private val routeSampler = object : Runnable {
        override fun run() {
            if (!callActive) return
            sampleRoute()
            handler.postDelayed(this, 2000)
        }
    }

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
            ACTION_ARM -> {
                AppSettings.setArmed(this, true)
                arm()
            }
            ACTION_DISARM -> {
                AppSettings.setArmed(this, false)
                disarm()
                stopSelf()
            }
            // Restarted by the system after being killed: carry on if the user left it armed.
            null -> if (AppSettings.armed(this)) arm() else stopSelf()
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
        routeSamples = 0
        speakerSamples = 0
        headsetSamples = 0
        handler.post(routeSampler)
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
        handler.removeCallbacks(routeSampler)
        val mode = when {
            routeSamples == 0 -> CaptureMode.EARPIECE
            speakerSamples * 2 >= routeSamples -> CaptureMode.SPEAKER
            headsetSamples * 2 >= routeSamples -> CaptureMode.HEADSET
            else -> CaptureMode.EARPIECE
        }
        Log.i(TAG, "Call ended; audio=${audioFile?.name}; mode=$mode; peak=$peak")

        worker.execute { processCall(audioFile, number, mode, peak) }
    }

    /** Where the call audio was going, which decides what the mic could hear. */
    enum class CaptureMode(val note: String) {
        SPEAKER("speakerphone"),
        EARPIECE("phone at ear: your side only"),
        HEADSET("headset: mostly your side only")
    }

    /** Samples the current call audio route (speaker / headset / earpiece). */
    private fun sampleRoute() {
        val am = getSystemService(AudioManager::class.java)
        routeSamples++
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.communicationDevice?.type else null
        @Suppress("DEPRECATION")
        when {
            type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || am.isSpeakerphoneOn -> speakerSamples++
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                am.isBluetoothScoOn -> headsetSamples++
        }
    }

    private fun processCall(audioFile: File?, number: String?, mode: CaptureMode, peak: Int) {
        // 1. Transcribe on-device with Vosk (offline, free; audio stays on the phone).
        var language = ""
        val transcript = try {
            when {
                audioFile == null -> "(No audio captured. Check the microphone permission.)"
                else -> {
                    val speaker = mode == CaptureMode.SPEAKER
                    val out = Transcriber.transcribe(this, audioFile, withVoiceprints = speaker)
                    when {
                        out == null -> "(Speech model missing from this build, so no transcript.)"
                        out.segments.isEmpty() -> "(No speech recognised in the recording.)"
                        else -> {
                            language = out.language.label
                            if (speaker) {
                                val labels = SpeakerLabeler.label(out.segments, AppSettings.voiceprint(this))
                                SpeakerLabeler.format(out.segments, labels)
                            } else {
                                // With the phone at your ear the mic only hears you.
                                "${SpeakerLabeler.YOU}: ${out.plainText}"
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            "(Transcription failed: ${e.message})"
        }
        // The transcript is all we need; don't keep call audio lying around.
        audioFile?.delete()

        // 2. Drafts: free-tier LLM if a key is set in the app, else offline rule-based drafts.
        val recordingNote = when (mode) {
            CaptureMode.SPEAKER -> "The call was on speakerphone, so both voices were recorded. " +
                "Lines are labelled by speaker (\"You\" is me when known)."
            else -> "Only my side of the call was recorded; the other person's words are missing."
        }
        val result = DraftGenerator.generate(this, transcript, recordingNote)
        val drafts = result.drafts

        // 3. Resolve the contact label, and offer to save the number if unknown.
        val label = ContactHelper.labelForNumber(this, number)
        if (number != null && !ContactHelper.isKnown(this, number)) {
            ContactHelper.maybeSaveUnknown(this, number)
        }

        // Capture diagnostic shown above the transcript.
        val silent = peak < 500
        val captureNote = listOf(
            mode.note + if (silent) " (very quiet: check mic permission)" else "",
            language,
            result.source
        ).filter { it.isNotBlank() }.joinToString(" · ")

        // 4. Save to history, then show the post-call overlay on the main thread.
        CallHistory.save(
            this,
            CallHistory.Record(System.currentTimeMillis(), number ?: "", label, captureNote, transcript, drafts)
        )
        mainExecutor.execute {
            OverlayManager(applicationContext).show(
                contactLabel = label,
                number = number ?: "Unknown",
                transcriptPreview = "[$captureNote]\n$transcript",
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
        handler.removeCallbacks(routeSampler)
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
