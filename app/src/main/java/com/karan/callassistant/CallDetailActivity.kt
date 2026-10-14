package com.karan.callassistant

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** One saved call: transcript plus the drafts, each still sendable. */
class CallDetailActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_call_detail)
        val id = intent.getLongExtra(EXTRA_ID, -1)
        val record = CallHistory.get(this, id)
        if (record == null) {
            finish()
            return
        }
        title = record.contactLabel
        val whenText = DateUtils.formatDateTime(
            this, record.id, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME
        )
        findViewById<TextView>(R.id.detailMeta).text =
            listOf(record.number, whenText, record.captureNote).filter { it.isNotBlank() }.joinToString("\n")
        findViewById<TextView>(R.id.detailTranscript).text = record.transcript

        val container = findViewById<LinearLayout>(R.id.detailDrafts)
        val inflater = LayoutInflater.from(this)
        record.drafts.forEach { draft ->
            val card = inflater.inflate(R.layout.item_draft, container, false)
            card.findViewById<TextView>(R.id.draftTitle).text = draft.title
            val edit = card.findViewById<EditText>(R.id.draftBody)
            edit.setText(draft.body)
            card.findViewById<Button>(R.id.btnWhatsApp).setOnClickListener {
                ShareHelper.shareToWhatsApp(this, edit.text.toString(), record.number)
            }
            card.findViewById<Button>(R.id.btnTelegram).setOnClickListener {
                ShareHelper.shareToTelegram(this, edit.text.toString())
            }
            card.findViewById<Button>(R.id.btnShare).setOnClickListener {
                ShareHelper.shareGeneric(this, edit.text.toString())
            }
            container.addView(card)
        }

        findViewById<Button>(R.id.detailDelete).setOnClickListener {
            CallHistory.delete(this, record.id)
            finish()
        }
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}
