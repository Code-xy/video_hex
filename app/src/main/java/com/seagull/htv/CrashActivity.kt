package com.seagull.htv

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** 崩溃现场：把堆栈摊在屏幕上，可复制 */
class CrashActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_PATH = "path"
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xFF101014.toInt())
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(this).apply {
            text = "闪退了 —— 把下面内容发给我"
            setTextColor(0xFFFF6B6B.toInt())
            textSize = 16f
        }

        val body = TextView(this).apply {
            this.text = text
            setTextColor(0xFFDDDDDD.toInt())
            textSize = 11f
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }

        val scroll = ScrollView(this).apply { addView(body) }

        val copy = Button(this).apply {
            text = "复制全部"
            setOnClickListener {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("crash", text))
                toast("已复制，粘贴发我即可")
            }
        }
        val close = Button(this).apply {
            text = "关闭"
            setOnClickListener { finish() }
        }

        root.addView(title)
        if (path.isNotEmpty()) {
            root.addView(TextView(this).apply {
                this.text = "堆栈文件: $path"
                setTextColor(0xFF88FFFFFF.toInt())
                textSize = 10f
            })
        }
        root.addView(scroll, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(copy)
        root.addView(close)

        setContentView(root)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
