package com.family.wxguard.core

import android.content.Context
import android.content.SharedPreferences
import com.family.wxguard.domain.Category
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Calendar

enum class InnerPolicy { BLOCK, QUOTA }

/** 家长密码校验结果。 */
enum class PassResult { OK, WRONG, LOCKED }

/**
 * 本地配置（SharedPreferences）：家长密码、每类内层容器的策略(禁止/限额)、
 * 学习日与周末配额、缓冲时长。
 */
object Cfg {
    private const val NAME = "guard_cfg"
    private const val MAX_PASSCODE_TRIES = 5
    private const val PASSCODE_LOCK_MS = 60_000L
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    // ---------- 家长密码 ----------
    val hasPasscode: Boolean get() = prefs.contains("pass_hash")

    fun setPasscode(raw: String) {
        val salt = randomHex(8)
        prefs.edit()
            .putString("pass_salt", salt)
            .putString("pass_hash", sha256(salt + raw))
            .apply()
    }

    fun verifyPasscode(raw: String): Boolean {
        val hash = prefs.getString("pass_hash", null) ?: return true
        val salt = prefs.getString("pass_salt", "") ?: ""
        return sha256(salt + raw) == hash
    }

    // ---------- 密码防暴力尝试：上限 + 冷却 ----------
    /** 距冷却结束的剩余毫秒；0 表示未在冷却。 */
    fun passcodeLockRemainMs(): Long {
        val remain = prefs.getLong("pass_lock_until", 0L) - System.currentTimeMillis()
        return if (remain > 0) remain else 0L
    }

    fun passcodeAttemptsLeft(): Int = MAX_PASSCODE_TRIES - prefs.getInt("pass_fails", 0)

    /** 带限流的校验：冷却中返回 [PassResult.LOCKED]；连续错满上限进入冷却。 */
    fun tryPasscode(raw: String): PassResult {
        if (passcodeLockRemainMs() > 0L) return PassResult.LOCKED
        if (verifyPasscode(raw)) {
            prefs.edit().remove("pass_fails").apply()
            return PassResult.OK
        }
        val fails = prefs.getInt("pass_fails", 0) + 1
        prefs.edit().putInt("pass_fails", fails).apply()
        if (fails < MAX_PASSCODE_TRIES) return PassResult.WRONG
        prefs.edit()
            .putLong("pass_lock_until", System.currentTimeMillis() + PASSCODE_LOCK_MS)
            .putInt("pass_fails", 0)
            .apply()
        return PassResult.LOCKED
    }

    // ---------- 策略 ----------
    fun policyFor(cat: Category): InnerPolicy {
        val key = when (cat) {
            Category.MINI_APP -> "policy_miniapp"
            Category.CHANNELS -> "policy_channels"
            else -> return InnerPolicy.BLOCK
        }
        val def = if (cat == Category.MINI_APP) InnerPolicy.QUOTA else InnerPolicy.BLOCK
        return runCatching { InnerPolicy.valueOf(prefs.getString(key, def.name)!!) }.getOrDefault(def)
    }

    fun setPolicy(cat: Category, policy: InnerPolicy) {
        val key = when (cat) {
            Category.MINI_APP -> "policy_miniapp"
            Category.CHANNELS -> "policy_channels"
            else -> return
        }
        prefs.edit().putString(key, policy.name).apply()
    }

    // ---------- 配额（分钟） ----------
    fun quotaMinutes(cat: Category, weekend: Boolean): Int {
        val (key, def) = when (cat) {
            Category.MINI_APP -> if (weekend) "quota_we_miniapp" to 60 else "quota_wd_miniapp" to 30
            Category.CHANNELS -> if (weekend) "quota_we_channels" to 30 else "quota_wd_channels" to 15
            else -> return 0
        }
        return prefs.getInt(key, def)
    }

    fun setQuotaMinutes(cat: Category, weekend: Boolean, minutes: Int) {
        val key = when (cat) {
            Category.MINI_APP -> if (weekend) "quota_we_miniapp" else "quota_wd_miniapp"
            Category.CHANNELS -> if (weekend) "quota_we_channels" else "quota_wd_channels"
            else -> return
        }
        prefs.edit().putInt(key, minutes.coerceAtLeast(0)).apply()
    }

    fun isWeekend(): Boolean {
        val day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        return day == Calendar.SATURDAY || day == Calendar.SUNDAY
    }

    // ---------- 缓冲（分钟） ----------
    fun graceMinutes(): Int = prefs.getInt("grace_min", 5).coerceIn(0, 180)

    fun setGraceMinutes(minutes: Int) {
        prefs.edit().putInt("grace_min", minutes.coerceAtLeast(0)).apply()
    }

    // ---------- 工具 ----------
    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }
}
