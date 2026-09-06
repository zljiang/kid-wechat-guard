package com.family.wxguard.guard

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.family.wxguard.core.CalibrationLog
import com.family.wxguard.core.ProcessState
import com.family.wxguard.domain.WechatFrontDetector

/**
 * 无障碍服务：只监听“前台窗口切换”，据此判定微信内层容器类别。
 * 不读取窗口内容（canRetrieveWindowContent=false），符合隐私边界。
 */
class WechatGuardAccessibilityService : AccessibilityService() {

    private var lastSignature: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        ProcessState.a11y = this
        CalibrationLog.recordEvent("a11y_connected")
        // 无障碍刚被用户启用：这是允许拉起前台服务的时机。
        GuardianForegroundService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString()
        val cls = event.className?.toString()

        // 忽略自身、系统 UI、状态栏等窗口，避免把“弹出我们自己的锁屏/下拉通知栏”误判成“离开微信内层容器”。
        if (pkg == null || pkg == packageName || pkg == "com.android.systemui" || pkg == "android") return

        val cat = WechatFrontDetector.classify(pkg, cls)
        ProcessState.currentPkg = pkg
        ProcessState.currentClass = cls

        val sig = "$pkg|$cls"
        if (sig != lastSignature) {
            lastSignature = sig
            CalibrationLog.record(pkg, cls ?: "", cat)
        }
        ProcessState.category = cat
        GuardEngine.onForegroundChanged(cat)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        CalibrationLog.recordEvent("a11y_unbound")
        ProcessState.a11y = null
        return super.onUnbind(intent)
    }
}
