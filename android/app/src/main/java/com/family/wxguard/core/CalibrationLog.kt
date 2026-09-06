package com.family.wxguard.core

import android.content.Context
import com.family.wxguard.domain.Category
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 校准日志：把每次前台窗口切换（包名 + 类名 + 判定类别）追加到当天日志文件，
 * 同时维护内存里的当天去重计数，供主界面实时观察，用于校准识别规则（R1）。
 */
object CalibrationLog {
    private const val DIR = "calib_logs"
    private var dir: File? = null
    private val dateFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    private var counts: Map<String, Int> = emptyMap()

    fun init(context: Context) {
        dir = File(context.filesDir, DIR).apply { mkdirs() }
    }

    private fun todayFile(): File? {
        val d = dir ?: return null
        return File(d, dateFmt.format(Date()) + ".log")
    }

    /** 每次前台(包名,类名)发生变化时调用。 */
    fun record(pkg: String, cls: String, cat: Category) {
        val line = "${timeFmt.format(Date())}\t$pkg\t$cls\t${cat.name}"
        try {
            todayFile()?.appendText(line + "\n")
        } catch (_: Exception) {
        }
        bump(cls, cat)
    }

    fun recordEvent(tag: String) {
        try {
            todayFile()?.appendText("${timeFmt.format(Date())}\t$tag\n")
        } catch (_: Exception) {
        }
    }

    private fun bump(cls: String, cat: Category) {
        synchronized(this) {
            val key = cat.name + " | " + (cls.ifBlank { "(no-class)" })
            counts = counts + (key to (counts[key] ?: 0) + 1)
        }
    }

    /** 当天去重统计：类名 → 出现次数，按次数倒序。 */
    fun snapshot(): List<Pair<String, Int>> = synchronized(this) {
        counts.toList().sortedByDescending { it.second }
    }

    /** 当天日志原始内容（最近 n 行），用于查看。 */
    fun recentLines(maxLines: Int = 200): String {
        val f = todayFile() ?: return ""
        return runCatching {
            f.readLines().takeLast(maxLines).joinToString("\n")
        }.getOrDefault("")
    }

    fun clearToday() {
        synchronized(this) {
            counts = emptyMap()
            runCatching { todayFile()?.delete() }
        }
    }
}
