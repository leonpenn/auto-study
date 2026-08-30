package com.autostudy.helper

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 运行日志：内存里保留最近 600 条（悬浮窗展示用），同时落盘到
 * Android/data/com.autostudy.helper/files/logs/ 下，方便导出排查。
 */
object LogRepo {
    private const val MAX_MEMORY_LINES = 600
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)
    private var ctx: Context? = null

    @Synchronized
    fun init(c: Context) {
        ctx = c.applicationContext
    }

    @Synchronized
    fun log(tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$tag] $msg"
        lines.addLast(line)
        while (lines.size > MAX_MEMORY_LINES) lines.removeFirst()
        appendFile(line)
    }

    private fun appendFile(line: String) {
        val c = ctx ?: return
        try {
            val dir = File(c.getExternalFilesDir(null), "logs")
            if (!dir.exists()) dir.mkdirs()
            val day = SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date())
            FileWriter(File(dir, "run-$day.log"), true).use { it.appendLine(line) }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    fun logsDir(c: Context): File {
        val dir = File(c.getExternalFilesDir(null), "logs")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
