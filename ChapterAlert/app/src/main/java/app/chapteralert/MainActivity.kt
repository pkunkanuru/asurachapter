package app.chapteralert

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.work.ExistingWorkPolicy
import org.json.JSONArray

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var updates: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private var syncPending = false

    // Runs on the (signed-in) bookmarks page: scrolls to load everything, then reports series links.
    private val collectScript = """
        (function() {
          var n = 0;
          function step() {
            window.scrollTo(0, document.body.scrollHeight);
            if (++n < 6) { setTimeout(step, 700); return; }
            var out = {};
            document.querySelectorAll('a[href]').forEach(function(a) {
              if (a.href.indexOf('https://asurascans.com/comics/') !== 0) return;
              var parts = new URL(a.href).pathname.split('/').filter(Boolean);
              if (parts.length === 2) out[a.href.split('?')[0].split('#')[0]] = 1;
            });
            Android.onLinks(JSON.stringify(Object.keys(out)));
          }
          setTimeout(step, 2000);
        })();
    """

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        CheckWorker.schedule(this, ExistingWorkPolicy.KEEP)

        // Reader: the real site in a WebView, so sign-in, bookmarks and premium chapters work normally.
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) { onLoaded(url) }
        }
        web.loadUrl("https://asurascans.com/")

        // Updates tab
        val hint = TextView(this)
        hint.text = "1. Open the Read tab and sign in.\n2. Tap Sync my bookmarks, or open any series and tap + Track.\nYou'll get a notification when a new chapter appears."
        val sync = Button(this); sync.text = "Sync my bookmarks"
        val now = Button(this); now.text = "Check for new chapters now"
        status = TextView(this); status.setPadding(0, 16, 0, 16)
        list = LinearLayout(this); list.orientation = LinearLayout.VERTICAL
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(40, 40, 40, 40)
        for (v in listOf<View>(hint, sync, now, status, list)) col.addView(v)
        updates = ScrollView(this)
        updates.addView(col)

        sync.setOnClickListener {
            syncPending = true
            status.text = "Opening your bookmarks..."
            web.loadUrl("https://asurascans.com/bookmarks/comics")
            showTab(true)
        }
        now.setOnClickListener {
            status.text = "Checking..."
            Thread {
                Tracker.check(this@MainActivity)
                runOnUiThread { status.text = "Done"; render() }
            }.start()
        }

        // Bottom bar
        val tabUpdates = Button(this); tabUpdates.text = "Updates"
        val tabRead = Button(this); tabRead.text = "Read"
        val track = Button(this); track.text = "+ Track"
        tabUpdates.setOnClickListener { showTab(false) }
        tabRead.setOnClickListener { showTab(true) }
        track.setOnClickListener {
            val u = web.url ?: ""
            if (!u.contains("asurascans.com/comics/")) {
                Toast.makeText(this@MainActivity, "Open a series first, then tap + Track", Toast.LENGTH_LONG).show()
                showTab(true)
            } else Thread {
                val msg = Tracker.add(this@MainActivity, u)
                runOnUiThread { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show(); render() }
            }.start()
        }
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        for (btn in listOf(tabUpdates, tabRead, track)) bar.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val frame = FrameLayout(this)
        frame.addView(updates)
        frame.addView(web)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        showTab(false)
        render()
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i == null) return
        val u = i.getStringExtra("open") ?: return
        i.removeExtra("open")
        web.loadUrl(u)
        showTab(true)
    }

    override fun onPause() { super.onPause(); CookieManager.getInstance().flush() }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (web.visibility == View.VISIBLE) { if (web.canGoBack()) web.goBack() else showTab(false) }
        else super.onBackPressed()
    }

    private fun showTab(read: Boolean) {
        web.visibility = if (read) View.VISIBLE else View.GONE
        updates.visibility = if (read) View.GONE else View.VISIBLE
        if (!read) render()
    }

    private fun onLoaded(url: String?) {
        if (!syncPending) return
        syncPending = false
        if (url == null || !url.contains("/bookmarks")) {
            Toast.makeText(this, "Sign in first, then tap Sync my bookmarks again", Toast.LENGTH_LONG).show()
            return
        }
        web.evaluateJavascript(collectScript, null)
    }

    inner class Bridge {
        @JavascriptInterface
        fun onLinks(json: String) {
            Thread {
                val arr = JSONArray(json)
                var ok = 0
                for (i in 0 until arr.length()) {
                    val msg = Tracker.add(this@MainActivity, arr.getString(i))
                    if (msg.startsWith("Tracking") || msg.startsWith("Already")) ok++
                    runOnUiThread { status.text = "Syncing ${i + 1}/${arr.length()}..." }
                }
                runOnUiThread {
                    status.text = if (arr.length() == 0) "No bookmarks found - are you signed in?"
                                  else "Tracking $ok of ${arr.length()} bookmarked series"
                    showTab(false)
                }
            }.start()
        }
    }

    private fun render() {
        list.removeAllViews()
        val items = Tracker.all(this)
        if (items.isEmpty()) {
            val empty = TextView(this); empty.text = "Nothing tracked yet."
            list.addView(empty)
        }
        for ((url, v) in items) {
            val parts = v.split("|", limit = 2)
            val title = parts.getOrElse(1) { url }
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(0, 24, 0, 24)
            val label = TextView(this)
            label.text = "$title\nChapter ${parts[0]}"
            label.textSize = 16f
            label.setOnClickListener { web.loadUrl(url); showTab(true) }
            val del = Button(this); del.text = "✕"
            del.setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage("Stop tracking $title?")
                    .setPositiveButton("Stop") { _, _ -> Tracker.remove(this@MainActivity, url); render() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(del)
            list.addView(row)
        }
    }
}
