package dev.coletz.opencfmoto

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The diagnostic log, moved off the home screen into its own screen so the main UI stays friendly.
 * Shows the full [LogBus] buffer and live updates while visible; Share exports it, Clear empties it.
 * Owns the [LogBus.listener] only while resumed — MainActivity reclaims it on return (see its
 * onResume/onPause), so exactly one screen drives the listener at a time.
 */
class LogActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_log)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.log_root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        logView = findViewById(R.id.log_view)
        logScroll = findViewById(R.id.log_scroll)
        logView.movementMethod = ScrollingMovementMethod()

        findViewById<Button>(R.id.btn_share).setOnClickListener { shareLog() }
        findViewById<Button>(R.id.btn_clear).setOnClickListener {
            LogBus.clear()
            logView.text = ""
        }
    }

    override fun onResume() {
        super.onResume()
        logView.text = LogBus.snapshot()
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        LogBus.listener = { line ->
            runOnUiThread {
                logView.append("$line\n")
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        LogBus.listener = null   // MainActivity reclaims it in its onResume
    }

    private fun shareLog() {
        try {
            val dir = File(cacheDir, "logs").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "opencfmoto-$stamp.log")
            file.writeText(LogBus.snapshot())
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "opencfmoto log $stamp")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share log"))
            LogBus.log("log saved: ${file.absolutePath} (${file.length()} bytes)")
        } catch (e: Exception) {
            LogBus.log("share failed: $e")
        }
    }
}
