package com.karan.callassistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Re-arms the call monitor after the phone restarts or the app is updated, if it was armed
 * before. If Android refuses to start it from the background (newer Android versions restrict
 * microphone services at boot), posts a notification that re-arms it with one tap.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!AppSettings.armed(context)) return
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CallMonitorService::class.java).setAction(CallMonitorService.ACTION_ARM)
            )
            Log.i(TAG, "Re-armed after $action")
        } catch (e: Exception) {
            Log.w(TAG, "Could not re-arm from background", e)
            notifyTapToArm(context)
        }
    }

    private fun notifyTapToArm(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Re-arm reminder", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_REARM)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Call Assistant is off")
            .setContentText("Tap to turn call monitoring back on")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            mgr.notify(7, n)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
        private const val CHANNEL_ID = "rearm"
    }
}
