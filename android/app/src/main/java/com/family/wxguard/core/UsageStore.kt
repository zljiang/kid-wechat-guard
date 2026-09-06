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
    private lateinit var prefs: SharedPreferences
    private val dateFmt = SimpleDateFormat("yyyyMMdd", Locale.US)

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    private fun today(): String = dateFmt.format(Date())

    fun usedSeconds(cat: Category): Long =
        prefs.getLong(key(today(), cat), 0L)

    fun addSeconds(cat: Category, seconds: Int) {
        val k = key(today(), cat)
        prefs.edit().putLong(k, prefs.getLong(k, 0L) + seconds.coerceAtLeast(0)).apply()
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
