package app.chapteralert

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.work.*

class MainActivity : Activity() {
    private lateinit var list: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        CheckWorker.schedule(this, ExistingWorkPolicy.KEEP)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(48, 96, 48, 48)
        val input = EditText(this)
        input.hint = "Paste series link (asurascans.com/comics/...)"
        input.setSingleLine()
        val add = Button(this); add.text = "Track series"
        val now = Button(this); now.text = "Check now"
        val status = TextView(this)
        list = LinearLayout(this); list.orientation = LinearLayout.VERTICAL

        add.setOnClickListener {
            val text = input.text.toString()
            status.text = "Checking..."
            Thread {
                val msg = Tracker.add(this@MainActivity, text)
                runOnUiThread { status.text = msg; input.setText(""); render() }
            }.start()
        }
        now.setOnClickListener {
            status.text = "Checking all series..."
            Thread {
                Tracker.check(this@MainActivity)
                runOnUiThread { status.text = "Done"; render() }
            }.start()
        }
        for (v in listOf(input, add, now, status, list)) root.addView(v)
        setContentView(ScrollView(this).apply { addView(root) })
        render()
    }

    private fun render() {
        list.removeAllViews()
        for ((url, v) in Tracker.all(this)) {
            val parts = v.split("|", limit = 2)
            val row = TextView(this)
            row.text = "${parts.getOrElse(1) { url }}  -  ch. ${parts[0]}\n(tap to open, long-press to remove)"
            row.setPadding(0, 32, 0, 32)
            row.setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            row.setOnLongClickListener { Tracker.remove(this@MainActivity, url); render(); true }
            list.addView(row)
        }
    }
}
