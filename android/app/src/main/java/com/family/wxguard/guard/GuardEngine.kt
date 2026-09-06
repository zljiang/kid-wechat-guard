package com.family.wxguard.guard

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.family.wxguard.core.Cfg
import com.family.wxguard.core.InnerPolicy
import com.family.wxguard.core.ProcessState
import com.family.wxguard.core.UsageStore
import com.family.wxguard.domain.Category
import com.family.wxguard.ui.LockActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class EngineState { NORMAL, GRACE, LOCKED }

enum class LockReason(val text: String) {
    BLOCKED_BY_POLICY("本类别已被设置为禁止使用"),
    QUOTA_EXCEEDED("今天的限额已用完")
}

/**
 * 守护引擎（单例，进程内）：
 * - 每秒采样 [ProcessState.category]；
 * - 禁止类容器：进入即锁定；
 * - 限额类容器：累计用量 → 配额耗尽进入缓冲 → 缓冲结束锁定；
 * - 离开被盯防的容器时自动解除锁定状态。
 */
object GuardEngine {
    private var context: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var ticker: Job? = null

    @Volatile var state: EngineState = EngineState.NORMAL
    @Volatile var lockReason: LockReason = LockReason.QUOTA_EXCEEDED
    @Volatile var activeCategory: Category = Category.OTHER
    @Volatile var graceRemainMs: Long = 0L
    @Volatile var lastTransitionAt: Long = 0L

    /** 家长密码解锁后：本次停留在该容器的会话内不再拦截，离开即失效。 */
    @Volatile var unlockUntilLeave: Boolean = false

    fun init(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = sc
        ticker = sc.launch {
            while (isActive) {
                tick()
                delay(1000)
            }
        }
    }

    fun destroy() {
        scope?.cancel()
        scope = null
        ticker = null
    }

    /** 无障碍服务每次判定前台类别变化后调用。 */
    fun onForegroundChanged(cat: Category) {
        val wasMonitored = activeCategory.monitored()
        val nowMonitored = cat.monitored()

        // 离开被盯防容器：清掉缓冲/锁定等会话状态（用量保留）。
        if (wasMonitored && !nowMonitored) {
            state = EngineState.NORMAL
            unlockUntilLeave = false
            graceRemainMs = 0
            NotificationHelper.cancelGraceReminder()
            dismissLock()
        }
        activeCategory = cat
        lastTransitionAt = System.currentTimeMillis()

        if (nowMonitored) evaluateNow()
    }

    private fun tick() {
        val cat = ProcessState.category
        if (cat != activeCategory) {
            activeCategory = cat
            if (activeCategory.monitored()) evaluateNow()
            return
        }
        if (!cat.monitored()) return
        // 处于锁定/本会话已放行时不做配额累计推进，但仍保持锁定状态展示
        if (state == EngineState.LOCKED) return
        // 无前台可见场景（熄屏）或无障碍感知通道不在线时，不累计、不推进状态，
        // 避免基于“最后一条窗口事件”的陈旧类别在熄屏/失联期间空计配额或误锁。
        if (!screenInteractive()) return
        if (ProcessState.a11y == null) return

        if (Cfg.policyFor(cat) == InnerPolicy.BLOCK) {
            lock(LockReason.BLOCKED_BY_POLICY)
            return
        }

        // QUOTA：先累计，再判定是否触发缓冲/锁定
        if (!unlockUntilLeave) {
            accumulateOneSecond(cat)
            maybeEnforceQuota(cat)
        }
    }

    private fun accumulateOneSecond(cat: Category) {
        UsageStore.addSeconds(cat, 1)
    }

    private fun maybeEnforceQuota(cat: Category) {
        val usedMs = UsageStore.usedSeconds(cat) * 1000
        val quotaMs = Cfg.quotaMinutes(cat, Cfg.isWeekend()) * 60_000L
        if (usedMs < quotaMs) {
            // 仍有配额：若此前在缓冲，配额不可能恢复，忽略
            return
        }
        // 配额耗尽
        if (state == EngineState.GRACE) {
            graceRemainMs -= 1000
            if (graceRemainMs <= 0) {
                graceRemainMs = 0
                NotificationHelper.cancelGraceReminder()
                lock(LockReason.QUOTA_EXCEEDED)
            } else {
                val sec = graceRemainMs / 1000
                NotificationHelper.showGraceReminder(
                    CfgCtx.title,
                    CfgCtx.graceBody(cat, sec)
                )
            }
        } else {
            val g = Cfg.graceMinutes() * 60_000L
            if (g <= 0) {
                lock(LockReason.QUOTA_EXCEEDED)
            } else {
                state = EngineState.GRACE
                graceRemainMs = g
                UsageStore.incrGrace(cat)
                NotificationHelper.showGraceReminder(
                    CfgCtx.title,
                    CfgCtx.graceBody(cat, g / 1000)
                )
            }
        }
    }

    /** 进入被盯防容器时的即时判定（禁止类秒拦、限额类若早已超额则立即走缓冲/锁定）。 */
    private fun evaluateNow() {
        val cat = activeCategory
        if (!cat.monitored()) return
        if (unlockUntilLeave) return
        if (state == EngineState.LOCKED) return

        val policy = Cfg.policyFor(cat)
        if (policy == InnerPolicy.BLOCK) {
            lock(LockReason.BLOCKED_BY_POLICY)
            return
        }
        val usedMs = UsageStore.usedSeconds(cat) * 1000
        val quotaMs = Cfg.quotaMinutes(cat, Cfg.isWeekend()) * 60_000L
        if (usedMs >= quotaMs && state == EngineState.NORMAL) {
            // 重新进入且今天已超额：直接给一次缓冲（若已处于缓冲则不打断）
            maybeEnforceQuota(cat)
        }
    }

    private fun lock(reason: LockReason) {
        if (state == EngineState.LOCKED) {
            ensureLockShown()
            return
        }
        state = EngineState.LOCKED
        lockReason = reason
        UsageStore.incrIntercept(activeCategory)
        NotificationHelper.cancelGraceReminder()
        ensureLockShown()
    }

    private fun ensureLockShown() {
        if (ProcessState.lockShowing) return
        val ctx = context ?: return
        ProcessState.lockCategory = activeCategory
        ProcessState.lockReason = lockReason.text
        mainHandler.post {
            if (ProcessState.lockShowing) return@post
            val i = Intent(ctx, LockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(i) }
        }
    }

    /** 家长密码解锁：本次会话放行。 */
    fun unlockByParent() {
        unlockUntilLeave = true
        state = EngineState.NORMAL
        graceRemainMs = 0
        NotificationHelper.cancelGraceReminder()
        dismissLock()
    }

    fun backToChat() {
        val a = ProcessState.a11y
        if (a != null) {
            a.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        }
        // 真正离开容器时由 onForegroundChanged 触发 dismissLock。
        // 若未离开（例如弹出了二次确认），锁定页仍保留并继续提示。
    }

    fun dismissLock() {
        mainHandler.post { ProcessState.dismissLock() }
    }

    /** 熄屏 / 锁屏状态下不做任何推进（避免空计配额）。 */
    private fun screenInteractive(): Boolean {
        val ctx = context ?: return false
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isInteractive
    }

    // ---------- 供界面读取的只读快照 ----------
    fun miniUsedMin(): Int = usedCeilMin(Category.MINI_APP)
    fun channelUsedMin(): Int = usedCeilMin(Category.CHANNELS)

    /** 向上取整到分钟，便于家长核对“今天已用满 N 分钟”。 */
    private fun usedCeilMin(cat: Category): Int =
        ((UsageStore.usedSeconds(cat) + 59) / 60).toInt()

    fun stateDescription(): String {
        return when (state) {
            EngineState.NORMAL -> when {
                activeCategory.monitored() && unlockUntilLeave -> "放行中（离开本页后恢复）"
                activeCategory.monitored() -> "盯防中（${activeCategory.label}）"
                else -> "空闲"
            }
            EngineState.GRACE -> "缓冲中，剩余 ${graceRemainMs / 1000} 秒"
            EngineState.LOCKED -> "已拦截：${ProcessState.lockCategory.label}"
        }
    }

    fun currentClassDescription(): String {
        val cls = ProcessState.currentClass ?: return "—"
        val cat = ProcessState.category
        return "${cat.name} | $cls"
    }
}

/** 引擎内使用的字符串封装，避免把长字符串写进逻辑类。 */
private object CfgCtx {
    val title: String = "使用时间提醒"
    fun graceBody(cat: Category, sec: Long): String = "${cat.label} 今日额度已用完，$sec 秒后将锁定"
}
