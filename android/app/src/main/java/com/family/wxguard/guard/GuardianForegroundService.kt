package com.family.wxguard.guard

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * 常驻前台服务：提供持续运行的进程存在感与常驻通知（保活 + 状态可见）。
 * 实际盯防逻辑在 [GuardEngine]（由 [WxGuardApp] 初始化后每秒 tick）。
 */
class GuardianForegroundService : Service() {

    companion object {
        private const val NOTIF_ID = 1

        @Volatile
        private var running = false

        fun isRunning(): Boolean = running

        /** 在允许启动前台服务的时机调用（前台 Activity / 无障碍服务刚启用 / 开机）。幂等且异常安全。 */
        fun start(context: Context) {
            if (running) return
            val ctx = context.applicationContext
            runCatching {
                val i = Intent(ctx, GuardianForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, NotificationHelper.guardNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
