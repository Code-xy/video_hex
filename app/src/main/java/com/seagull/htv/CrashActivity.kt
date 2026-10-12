package com.seagull.htv

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** 崩溃现场：把堆栈摊在屏幕上，可一键复制，没 adb 也能取证 */
class CrashActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_PATH = "path"
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_crash)

        val trace = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val file = intent.getStringExtra(EXTRA_PATH).orEmpty()

        val body = findViewById<TextView>(R.id.body)
        val path = findViewById<TextView>(R.id.path)

        body.text = trace
        path.text = if (file.isEmpty()) "" else getString(R.string.crash_file, file)
        path.visibility = if (file.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE

        findViewById<Button>(R.id.copy).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("crash", trace))
            Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.close).setOnClickListener { finish() }
    }
}
