package com.karan.callassistant

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.ListView
import android.widget.SimpleAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Lists past calls, newest first. Tap one to see its transcript and drafts again. */
class HistoryActivity : AppCompatActivity() {

    private var records: List<CallHistory.Record> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        title = getString(R.string.call_history)
        val list = findViewById<ListView>(R.id.historyList)
        list.emptyView = findViewById<TextView>(R.id.historyEmpty)
        list.setOnItemClickListener { _, _, pos, _ ->
            startActivity(
                Intent(this, CallDetailActivity::class.java)
                    .putExtra(CallDetailActivity.EXTRA_ID, records[pos].id)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        records = CallHistory.list(this)
        val rows = records.map { r ->
            val whenText = DateUtils.formatDateTime(
                this, r.id,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
            )
            mapOf(
                "title" to "${r.contactLabel} · $whenText",
                "sub" to r.transcript.replace('\n', ' ').take(120)
            )
        }
        findViewById<ListView>(R.id.historyList).adapter = SimpleAdapter(
            this, rows, android.R.layout.simple_list_item_2,
            arrayOf("title", "sub"), intArrayOf(android.R.id.text1, android.R.id.text2)
        )
    }
}
