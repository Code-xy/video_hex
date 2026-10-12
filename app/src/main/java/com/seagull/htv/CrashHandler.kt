package com.seagull.htv

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃捕获：把堆栈写进文件，并拉起 CrashActivity 显示（可一键复制）。
 * 目的是让没有 adb 的设备也能把真实堆栈交出来。
 */
object CrashHandler {

    private const val DIR = "crash"

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val head = buildString {
                    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    appendLine("时间: " + fmt.format(Date()))
                    appendLine("线程: " + thread.name)
                    appendLine("机型: " + Build.MANUFACTURER + " " + Build.MODEL)
                    appendLine("Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")
                    appendLine("App: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
                    appendLine("ABI: " + Build.SUPPORTED_ABIS.joinToString())
                    appendLine()
                }
                val text = head + sw.toString()

                val dir = File(app.filesDir, DIR).apply { mkdirs() }
                val f = File(dir, "crash-" + System.currentTimeMillis() + ".txt")
                f.writeText(text)
                // 同时留一份最新的，方便只拿一个文件
                File(dir, "last-crash.txt").writeText(text)

                val i = Intent(app, CrashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra(CrashActivity.EXTRA_TEXT, text)
                    putExtra(CrashActivity.EXTRA_PATH, f.absolutePath)
                }
                app.startActivity(i)
            } catch (t: Throwable) {
                // 捕获器自身出错就别再闹，交回系统
            }
            prev?.uncaughtException(thread, e)
        }
    }
}
