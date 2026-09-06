package com.family.wxguard

import android.app.Application
import com.family.wxguard.core.CalibrationLog
import com.family.wxguard.core.Cfg
import com.family.wxguard.core.UsageStore
import com.family.wxguard.guard.GuardEngine
import com.family.wxguard.guard.NotificationHelper

class WxGuardApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Cfg.init(this)
        UsageStore.init(this)
        CalibrationLog.init(this)
        NotificationHelper.init(this)
        GuardEngine.init(this)
    }
}
