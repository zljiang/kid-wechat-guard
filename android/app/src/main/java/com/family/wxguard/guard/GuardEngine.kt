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
import java.util.EnumMap

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
 * - 缓冲/锁定状态按 [Category] 独立维护（[CatState]），跨"离开容器"保留：
 *   离开仅暂停，重新进入继续倒计时/恢复锁定页，防止"退出重进"刷新缓冲；
 *   按类别隔离也杜绝了"换类别一进一出"重置另一类别缓冲的跨类别污染；
 *   仅该类别自身配额恢复（次日清零/家长上调）时复位。
 * - 所有状态迁移经 [engineLock] 串行化（ticker 后台线程 × 无障碍主线程），
 *   避免进入瞬间的复合迁移竞态（双发缓冲/拦截）。
 */
object GuardEngine {
    private var context: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var ticker: Job? = null

    /** 状态迁移统一互斥锁；锁内只做内存读写与 post，不等待主线程，无死锁风险。 */
    private val engineLock = Any()

    @Volatile var activeCategory: Category = Category.OTHER
    @Volatile var lastTransitionAt: Long = 0L

    /** 当前缓冲提醒通知所属的类别；null = 当前无提醒（离开容器/已锁定时取消）。 */
    @Volatile private var graceReminderCat: Category? = null

    /** 单个被盯防类别的守护状态（配额/缓冲/锁定互不串扰）。 */
    private class CatState {
        @Volatile var state: EngineState = EngineState.NORMAL
        @Volatile var graceRemainMs: Long = 0L
        @Volatile var unlockUntilLeave: Boolean = false
        @Volatile var lockReason: LockReason = LockReason.QUOTA_EXCEEDED
    }

    private val catStates = EnumMap<Category, CatState>(Category::class.java)

    private fun catState(c: Category): CatState = synchronized(catStates) {
        catStates.getOrPut(c) { CatState() }
    }

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
        synchronized(engineLock) {
            val was = activeCategory
            // 切到非盯防窗口、或直接切到另一个被盯防类别（mini→channels），
            // 都要结束上一类别的会话状态（解锁失效/用量落盘）。
            if (was.monitored() && was != cat) handleLeftContainer(was)
            activeCategory = cat
            lastTransitionAt = System.currentTimeMillis()

            if (cat.monitored()) evaluateNow(cat)
        }
    }

    /** 离开被盯防容器：会话相关状态复位（家长放行、锁定页、用量落盘）。
     *  该类别自身的 GRACE/LOCKED 保留——重新进入时继续推进，缓冲剩余时间不重置。 */
    private fun handleLeftContainer(fromCat: Category) {
        catState(fromCat).unlockUntilLeave = false
        cancelGraceReminder()
        UsageStore.flush()
        dismissLock()
    }

    /** 取消缓冲提醒并清除其类别标记（重进续跑缓冲时按标记补发）。 */
    private fun cancelGraceReminder() {
        graceReminderCat = null
        NotificationHelper.cancelGraceReminder()
    }

    private fun tick() = synchronized(engineLock) {
        val cur = ProcessState.category
        if (cur != activeCategory) {
            // 兜底对齐前台类别（无障碍事件偶发丢失/合并时，靠采样发现变化）。
            val was = activeCategory
            activeCategory = cur
            if (was.monitored() && was != cur) handleLeftContainer(was)
            if (cur.monitored()) evaluateNow(cur)
            return@synchronized
        }
        if (!cur.monitored()) return
        // 熄屏 / 无障碍失联期间不推进任何状态（含锁定页重拉与缓冲倒计时），
        // 避免空计配额、误锁，以及熄屏时反复尝试后台拉起 Activity。
        if (!screenInteractive()) return
        if (ProcessState.a11y == null) return

        val cs = catState(cur)
        // 锁定状态下保持锁定页常驻：即使锁定页被意外关闭（如代按返回仅关掉了页面），
        // 下一个 tick 也会重新拉起，直到离开容器或家长解锁。
        if (cs.state == EngineState.LOCKED) {
            ensureLockShown(cur)
            return
        }
        if (Cfg.policyFor(cur) == InnerPolicy.BLOCK) {
            lock(cur, LockReason.BLOCKED_BY_POLICY)
            return
        }
        // QUOTA：先累计，再判定是否触发缓冲/锁定
        if (!cs.unlockUntilLeave) {
            accumulateOneSecond(cur)
            maybeEnforceQuota(cur)
        }
    }

    private fun accumulateOneSecond(cat: Category) {
        UsageStore.addSeconds(cat, 1)
    }

    private fun maybeEnforceQuota(cat: Category) {
        val cs = catState(cat)
        val usedMs = UsageStore.usedSeconds(cat) * 1000
        val quotaMs = Cfg.quotaMinutes(cat, Cfg.isWeekend()) * 60_000L
        if (usedMs < quotaMs) {
            // 配额未用尽（次日清零/家长上调）：复位本类别挂着的缓冲状态。
            if (cs.state == EngineState.GRACE) {
                cs.state = EngineState.NORMAL
                cs.graceRemainMs = 0
                cancelGraceReminder()
            }
            return
        }
        // 配额耗尽：缓冲已在进行 → 推进倒计时（离开容器期间暂停，回到容器继续）；
        // 否则给一次缓冲（每次耗尽只给一轮，耗尽状态跨离开保留，不会重复发）。
        if (cs.state == EngineState.GRACE) {
            cs.graceRemainMs -= 1000
            if (cs.graceRemainMs <= 0) {
                cs.graceRemainMs = 0
                cancelGraceReminder()
                lock(cat, LockReason.QUOTA_EXCEEDED)
            } else if (graceReminderCat != cat) {
                // 提醒在离开容器/切换类别时已被取消：重新进入续跑缓冲时补发一次，
                // 倒计时从剩余时间起算；不随 tick 每秒重发。
                NotificationHelper.showGraceReminder(
                    CfgCtx.title,
                    CfgCtx.graceBody(cat),
                    cs.graceRemainMs
                )
                graceReminderCat = cat
            }
        } else {
            val g = Cfg.graceMinutes() * 60_000L
            if (g <= 0) {
                lock(cat, LockReason.QUOTA_EXCEEDED)
            } else {
                cs.state = EngineState.GRACE
                cs.graceRemainMs = g
                UsageStore.incrGrace(cat)
                NotificationHelper.showGraceReminder(
                    CfgCtx.title,
                    CfgCtx.graceBody(cat),
                    g
                )
                graceReminderCat = cat
            }
        }
    }

    /** 进入被盯防容器时的即时判定（禁止类秒拦、限额类若早已超额则立即走缓冲/锁定）。 */
    private fun evaluateNow(cat: Category) {
        val cs = catState(cat)
        if (cs.unlockUntilLeave) return

        // 本类别配额已恢复（次日清零/家长上调）时清除其跨会话保留的缓冲/锁定状态，
        // 避免"次日进入秒锁"。只看本类别，不影响其他类别的状态。
        if (cs.state != EngineState.NORMAL &&
            Cfg.policyFor(cat) == InnerPolicy.QUOTA &&
            UsageStore.usedSeconds(cat) * 1000 <
            Cfg.quotaMinutes(cat, Cfg.isWeekend()) * 60_000L
        ) {
            cs.state = EngineState.NORMAL
            cs.graceRemainMs = 0
            cancelGraceReminder()
        }

        when (cs.state) {
            EngineState.LOCKED -> {
                // 曾被锁定：离开容器不重置，重新进入立即恢复锁定页（缓冲已消耗完毕，不再重发）。
                ensureLockShown(cat)
                return
            }
            EngineState.GRACE -> return // 缓冲暂停后重新进入，由 tick 继续倒计时，不重新计时。
            EngineState.NORMAL -> {}
        }

        if (Cfg.policyFor(cat) == InnerPolicy.BLOCK) {
            lock(cat, LockReason.BLOCKED_BY_POLICY)
            return
        }
        val usedMs = UsageStore.usedSeconds(cat) * 1000
        val quotaMs = Cfg.quotaMinutes(cat, Cfg.isWeekend()) * 60_000L
        if (usedMs >= quotaMs) {
            maybeEnforceQuota(cat)
        }
    }

    private fun lock(cat: Category, reason: LockReason) {
        val cs = catState(cat)
        if (cs.state == EngineState.LOCKED) {
            ensureLockShown(cat)
            return
        }
        cs.state = EngineState.LOCKED
        cs.lockReason = reason
        UsageStore.incrIntercept(cat)
        UsageStore.flush()
        cancelGraceReminder()
        ensureLockShown(cat)
    }

    private fun ensureLockShown(cat: Category) {
        if (ProcessState.lockShowing) return
        val ctx = context ?: return
        ProcessState.lockCategory = cat
        // 锁定文案跟随该类别当前的实际策略，避免策略变更后文案错位。
        ProcessState.lockReason =
            if (Cfg.policyFor(cat) == InnerPolicy.BLOCK) LockReason.BLOCKED_BY_POLICY.text
            else catState(cat).lockReason.text
        mainHandler.post {
            if (ProcessState.lockShowing) return@post
            val i = Intent(ctx, LockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(i) }
        }
    }

    /** 家长密码解锁：当前类别的本次会话放行，离开即失效（其他类别状态不受影响）。 */
    fun unlockByParent() {
        synchronized(engineLock) {
            val cs = catState(activeCategory)
            cs.unlockUntilLeave = true
            cs.state = EngineState.NORMAL
            cs.graceRemainMs = 0
            cancelGraceReminder()
            dismissLock()
        }
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

    /** 向上取整到分钟，便于家长核对"今天已用满 N 分钟"。 */
    private fun usedCeilMin(cat: Category): Int =
        ((UsageStore.usedSeconds(cat) + 59) / 60).toInt()

    fun stateDescription(): String {
        val cs = catState(activeCategory)
        return when (cs.state) {
            EngineState.NORMAL -> when {
                activeCategory.monitored() && cs.unlockUntilLeave -> "放行中（离开本页后恢复）"
                activeCategory.monitored() -> "盯防中（${activeCategory.label}）"
                else -> "空闲"
            }
            EngineState.GRACE -> "缓冲中，剩余 ${cs.graceRemainMs / 1000} 秒"
            EngineState.LOCKED -> "已拦截：${activeCategory.label}"
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
    fun graceBody(cat: Category): String = "${cat.label} 今日额度已用完，缓冲结束后将锁定"
}
