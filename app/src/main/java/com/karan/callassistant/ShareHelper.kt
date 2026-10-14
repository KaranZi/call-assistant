package com.karan.callassistant

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Opens WhatsApp / Telegram / the system share sheet with the chosen draft pre-filled.
 *
 * Important reality: these intents only *pre-fill* the message. Android (and WhatsApp /
 * Telegram themselves) will not let a third-party app silently send on the user's behalf —
 * the user still taps send in the target app. That's by design and there is no way around it.
 */
object ShareHelper {

    private const val WHATSAPP_PKG = "com.whatsapp"
    private const val TELEGRAM_PKG = "org.telegram.messenger"

    fun shareToWhatsApp(context: Context, text: String, number: String?) {
        // If we have a number, wa.me can target that chat directly.
        val digits = number?.filter { it.isDigit() }
        if (!digits.isNullOrEmpty()) {
            val uri = Uri.parse("https://wa.me/$digits?text=${Uri.encode(text)}")
            if (launchView(context, uri, WHATSAPP_PKG)) return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(WHATSAPP_PKG)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        launch(context, intent, "WhatsApp", text)
    }

    fun shareToTelegram(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(TELEGRAM_PKG)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        launch(context, intent, "Telegram", text)
    }

    fun shareGeneric(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Share via").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun launchView(context: Context, uri: Uri, pkg: String): Boolean {
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    private fun launch(context: Context, intent: Intent, appName: String, fallbackText: String) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "$appName not installed — using share sheet", Toast.LENGTH_SHORT).show()
            shareGeneric(context, fallbackText)
        }
    }
}
