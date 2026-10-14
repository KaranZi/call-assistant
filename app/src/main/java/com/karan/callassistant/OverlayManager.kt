package com.karan.callassistant

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Draws the post-call pop-up over whatever is on screen using a SYSTEM_ALERT_WINDOW
 * (TYPE_APPLICATION_OVERLAY). Shows the transcript preview and the suggested drafts,
 * each editable, each with WhatsApp / Telegram / Share buttons.
 *
 * Requires the "display over other apps" permission (Settings.canDrawOverlays).
 */
class OverlayManager(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: View? = null

    fun show(
        contactLabel: String,
        number: String,
        transcriptPreview: String,
        drafts: List<DraftGenerator.Draft>
    ) {
        dismiss()

        val view = LayoutInflater.from(context).inflate(R.layout.overlay_suggestions, null)
        view.findViewById<TextView>(R.id.overlayTitle).text =
            context.getString(R.string.overlay_title, contactLabel)
        view.findViewById<TextView>(R.id.overlayTranscript).text = transcriptPreview

        val container = view.findViewById<LinearLayout>(R.id.draftsContainer)
        val inflater = LayoutInflater.from(context)
        drafts.forEach { draft ->
            val card = inflater.inflate(R.layout.item_draft, container, false)
            card.findViewById<TextView>(R.id.draftTitle).text = draft.title
            val edit = card.findViewById<EditText>(R.id.draftBody)
            edit.setText(draft.body)

            card.findViewById<Button>(R.id.btnWhatsApp).setOnClickListener {
                ShareHelper.shareToWhatsApp(context, edit.text.toString(), number)
            }
            card.findViewById<Button>(R.id.btnTelegram).setOnClickListener {
                ShareHelper.shareToTelegram(context, edit.text.toString())
            }
            card.findViewById<Button>(R.id.btnShare).setOnClickListener {
                ShareHelper.shareGeneric(context, edit.text.toString())
            }
            container.addView(card)
        }

        view.findViewById<Button>(R.id.btnClose).setOnClickListener { dismiss() }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        // The EditTexts need focus to type into; drop NOT_FOCUSABLE once shown.
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()

        try {
            windowManager.addView(view, params)
            root = view
        } catch (e: Exception) {
            // Overlay permission missing or window add failed; nothing to show.
        }
    }

    fun dismiss() {
        root?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        root = null
    }
}
