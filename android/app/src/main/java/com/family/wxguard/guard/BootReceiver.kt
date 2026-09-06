package com.family.wxguard.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机自启：重启后重新拉起常驻前台服务（无障碍由系统自动重连）。 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return
        GuardianForegroundService.start(context)
    }
}
