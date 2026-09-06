package com.family.wxguard.core

import android.app.Activity
import com.family.wxguard.domain.Category
import com.family.wxguard.guard.GuardianForegroundService
import com.family.wxguard.guard.WechatGuardAccessibilityService
import com.family.wxguard.ui.LockActivity

/** 进程内共享的运行时状态：由 a11y 服务写入，引擎/界面读取。 */
object ProcessState {
    @Volatile var category: Category = Category.OTHER
    @Volatile var currentPkg: String? = null
    @Volatile var currentClass: String? = null

    /** 已连接的无障碍服务（用于代按返回键退出内层容器）。 */
    @Volatile var a11y: WechatGuardAccessibilityService? = null

    /** 正在显示中的锁定页（若存在）。 */
    @Volatile var lockActivity: LockActivity? = null

    val lockShowing: Boolean get() = lockActivity != null

    /** 守护前台服务是否在运行（供界面展示）。 */
    fun foregroundServiceRunning(): Boolean = GuardianForegroundService.isRunning()

    /** 无障碍服务是否已被用户在系统中开启。 */
    fun accessibilityEnabled(ctx: android.content.Context): Boolean {
        val enabled = android.provider.Settings.Secure.getString(
            ctx.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val expected = "${ctx.packageName}/${WechatGuardAccessibilityService::class.java.name}"
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    /** 界面跳转用：当前锁屏内容（引擎线程写、主线程读）。 */
    @Volatile var lockCategory: Category = Category.OTHER
    @Volatile var lockReason: String = ""

    fun dismissLock() {
        val a = lockActivity
        lockActivity = null
        a?.let { if (!it.isFinishing) it.finish() }
        lockCategory = Category.OTHER
    }

    fun registerActivity(activity: Activity) {
        if (activity is LockActivity) lockActivity = activity
    }

    fun unregisterActivity(activity: Activity) {
        if (activity is LockActivity) lockActivity = null
    }
}
