package com.family.wxguard.core

import android.content.Context
import android.content.SharedPreferences
import com.family.wxguard.domain.Category
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 本地用量记录：按 日期 + 类别 累计秒数，以及拦截/缓冲次数。 */
object UsageStore {
    private const val NAME = "guard_usage"
    private const val FLUSH_SECONDS = 60L
    private lateinit var prefs: SharedPreferences
    private val dateFmt = SimpleDateFormat("yyyyMMdd", Locale.US)

    /** 内存中未落盘的秒数（key 与磁盘一致），凑满 [FLUSH_SECONDS] 才写盘，避免每秒写 SharedPreferences。 */
    private val pendingSeconds = HashMap<String, Long>()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    private fun today(): String = dateFmt.format(Date())

    @Synchronized
    fun usedSeconds(cat: Category): Long {
        val k = key(today(), cat)
        return prefs.getLong(k, 0L) + (pendingSeconds[k] ?: 0L)
    }

    @Synchronized
    fun addSeconds(cat: Category, seconds: Int) {
        val k = key(today(), cat)
        val v = (pendingSeconds[k] ?: 0L) + seconds.coerceAtLeast(0)
        if (v >= FLUSH_SECONDS) {
            prefs.edit().putLong(k, prefs.getLong(k, 0L) + v).apply()
            pendingSeconds.remove(k)
        } else {
            pendingSeconds[k] = v
        }
    }

    /** 把内存中未满一分钟的秒数落盘（离开容器/锁定等会话边界调用，异常退出最多丢 1 分钟）。 */
    @Synchronized
    fun flush() {
        if (pendingSeconds.isEmpty()) return
        val e = prefs.edit()
        for ((k, v) in pendingSeconds) e.putLong(k, prefs.getLong(k, 0L) + v)
        e.apply()
        pendingSeconds.clear()
    }

    fun incrIntercept(cat: Category) {
        incr("ct_" + cat.name)
    }

    fun interceptCount(cat: Category): Long = prefs.getLong(countKey("ct_" + cat.name), 0L)

    fun incrGrace(cat: Category) {
        incr("grace_" + cat.name)
    }

    fun graceCount(cat: Category): Long = prefs.getLong(countKey("grace_" + cat.name), 0L)

    private fun key(date: String, cat: Category) = "u_${date}_${cat.name}"

    private fun countKey(name: String) = "${today()}_$name"

    private fun incr(name: String) {
        val k = countKey(name)
        prefs.edit().putLong(k, prefs.getLong(k, 0L) + 1).apply()
    }
}
