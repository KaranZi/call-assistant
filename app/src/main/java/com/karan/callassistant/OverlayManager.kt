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
import android.widget.Toast

/**
 * Draws the post-call pop-up over whatever is on screen using a SYSTEM_ALERT_WINDOW
 * (TYPE_APPLICATION_OVERLAY). Shows the transcript, the suggested drafts (each editable with
 * WhatsApp / Telegram / Share buttons), and, when the caller isn't in the address book, a
 * row to save them to contacts.
 *
 * Behaviour the user asked for: saving a contact keeps the pop-up open; tapping any messaging
 * button sends the draft and closes the pop-up.
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
        numberKnown: Boolean,
        transcriptPreview: String,
        drafts: List<DraftGenerator.Draft>
    ) {
        dismiss()

        val view = LayoutInflater.from(context).inflate(R.layout.overlay_suggestions, null)
        view.findViewById<TextView>(R.id.overlayTitle).text =
            context.getString(R.string.overlay_title, contactLabel)
        view.findViewById<TextView>(R.id.overlayTranscript).text = transcriptPreview

        setUpSaveRow(view, number, numberKnown)

        val container = view.findViewById<LinearLayout>(R.id.draftsContainer)
        val inflater = LayoutInflater.from(context)
        drafts.forEach { draft ->
            val card = inflater.inflate(R.layout.item_draft, container, false)
            card.findViewById<TextView>(R.id.draftTitle).text = draft.title
            val edit = card.findViewById<EditText>(R.id.draftBody)
            edit.setText(draft.body)

            // Sending a draft opens the target app and closes this pop-up.
            card.findViewById<Button>(R.id.btnWhatsApp).setOnClickListener {
                ShareHelper.shareToWhatsApp(context, edit.text.toString(), number)
                dismiss()
            }
            card.findViewById<Button>(R.id.btnTelegram).setOnClickListener {
                ShareHelper.shareToTelegram(context, edit.text.toString())
                dismiss()
            }
            card.findViewById<Button>(R.id.btnShare).setOnClickListener {
                ShareHelper.shareGeneric(context, edit.text.toString())
                dismiss()
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
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        try {
            windowManager.addView(view, params)
            root = view
        } catch (e: Exception) {
            // Overlay permission missing or window add failed; nothing to show.
        }
    }

    /** Shows a "save this number" row for unknown callers; saving leaves the pop-up open. */
    private fun setUpSaveRow(view: View, number: String, numberKnown: Boolean) {
        val row = view.findViewById<LinearLayout>(R.id.saveContactRow)
        val hasNumber = number.isNotBlank() && number != "Unknown"
        if (numberKnown || !hasNumber) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        val nameInput = view.findViewById<EditText>(R.id.contactNameInput)
        val saveButton = view.findViewById<Button>(R.id.btnSaveContact)
        saveButton.setOnClickListener {
            val ok = ContactHelper.saveContact(context, nameInput.text.toString(), number)
            if (ok) {
                Toast.makeText(context, R.string.contact_saved, Toast.LENGTH_SHORT).show()
                row.visibility = View.GONE
            } else {
                Toast.makeText(context, R.string.contact_save_failed, Toast.LENGTH_LONG).show()
            }
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
