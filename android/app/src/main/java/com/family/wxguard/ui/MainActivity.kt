package com.family.wxguard.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.family.wxguard.R
import com.family.wxguard.core.CalibrationLog
import com.family.wxguard.core.Cfg
import com.family.wxguard.core.ProcessState
import com.family.wxguard.core.UsageStore
import com.family.wxguard.domain.Category
import com.family.wxguard.guard.GuardEngine
import com.family.wxguard.guard.GuardianForegroundService

/** 状态首页：守护状态 + 今日用量 + 校准观察 + 入口。 */
class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var contentReady = false
    private val refresh = object : Runnable {
        override fun run() {
            updateUi()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 启动密码门：家长密码保护本页（用量/校准日志/入口）不被孩子查看。
        PassUi.showParentGate(this, onOk = { initContent() }, onBlocked = { finish() })
    }

    private fun initContent() {
        setContentView(R.layout.activity_main)
        contentReady = true

        findViewById<Button>(R.id.btnEnableA11y).setOnClickListener { onEnableA11y() }
        findViewById<Button>(R.id.btnBatteryOpt).setOnClickListener { onIgnoreBattery() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnExport).setOnClickListener { showRawLog() }

        // 无障碍就绪时立刻拉起常驻前台服务（幂等），保证守护进程持续存活。
        if (ProcessState.accessibilityEnabled(this)) {
            GuardianForegroundService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        if (contentReady) handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
    }

    private fun onEnableA11y() {
        requestNotificationPermissionIfNeeded()
        if (!ProcessState.accessibilityEnabled(this)) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } else {
            Toast("无障碍已开启；请在微信里打开一次小程序，回来看校准记录")
        }
    }

    private fun onIgnoreBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast("已忽略电池优化")
            return
        }
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun updateUi() {
        val sb = StringBuilder()

        val a11yOn = ProcessState.accessibilityEnabled(this)
        sb.append(getString(R.string.a11y_status))
            .append(if (a11yOn) "：已开启\n" else "：未开启（点击下方按钮去系统设置开启）\n")

        sb.append(getString(R.string.guard_service_status))
            .append(if (ProcessState.foregroundServiceRunning()) "：运行中\n" else "：未运行\n")

        val pm = getSystemService(PowerManager::class.java)
        sb.append("电池优化：")
            .append(if (pm.isIgnoringBatteryOptimizations(packageName)) "已忽略\n" else "未忽略（建议点击下方按钮）\n")

        sb.append(getString(R.string.engine_state))
            .append("：").append(GuardEngine.stateDescription()).append('\n')
        sb.append("当前窗口：").append(GuardEngine.currentClassDescription()).append('\n')

        sb.append("---- ").append(getString(R.string.today_usage)).append(" ----\n")
        sb.append("小程序/小游戏：").append(GuardEngine.miniUsedMin())
            .append(" 分钟（今日拦截 ").append(UsageStore.interceptCount(Category.MINI_APP)).append(" 次）\n")
        sb.append("视频号：").append(GuardEngine.channelUsedMin())
            .append(" 分钟（今日拦截 ").append(UsageStore.interceptCount(Category.CHANNELS)).append(" 次）\n")
        sb.append("配额参考：学习日/周末 小程序 ")
            .append(Cfg.quotaMinutes(Category.MINI_APP, false)).append('/')
            .append(Cfg.quotaMinutes(Category.MINI_APP, true))
            .append(" 分钟；缓冲 ").append(Cfg.graceMinutes()).append(" 分钟\n")

        findViewById<TextView>(R.id.statusText).text = sb.toString()

        val calib = CalibrationLog.snapshot()
        findViewById<TextView>(R.id.calibText).text =
            if (calib.isEmpty()) getString(R.string.hint_no_log)
            else calib.take(20).joinToString("\n") { (k, c) -> "$k  ×$c" }
    }

    private fun showRawLog() {
        val raw = CalibrationLog.recentLines()
        AlertDialog.Builder(this)
            .setTitle(R.string.calib_title)
            .setMessage(if (raw.isBlank()) getString(R.string.hint_no_log) else raw)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun Toast(msg: CharSequence) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
