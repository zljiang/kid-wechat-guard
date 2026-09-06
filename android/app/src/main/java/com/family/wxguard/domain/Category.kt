package com.family.wxguard.domain

enum class Category(val label: String) {
    OTHER("其它"),
    WECHAT_CHAT("微信聊天"),
    MINI_APP("小程序/小游戏"),
    CHANNELS("视频号");

    /** 是否需要被守护盯防（计时/拦截）的内层容器。 */
    fun monitored(): Boolean = this == MINI_APP || this == CHANNELS
}

/**
 * 把前台窗口的 (包名, Activity类名) 归为某个 [Category]。
 *
 * 注意：类名规则需在真机上校准（见 docs/design.md R1），微信改版可能变化。
 * 未知的微信内部界面暂时归为 WECHAT_CHAT，但每次切换都会被 CalibrationLog
 * 完整记录，便于家长/开发者发现新的容器类名后补充规则。
 */
object WechatFrontDetector {

    const val WECHAT_PKG = "com.tencent.mm"

    fun classify(pkg: String?, cls: String?): Category {
        if (pkg == WECHAT_PKG) {
            val c = cls?.lowercase().orEmpty()
            return when {
                c.contains("appbrand") -> Category.MINI_APP   // 小程序 / 小游戏容器
                c.contains("finder") -> Category.CHANNELS     // 视频号
                c.contains("launcherui") ||
                    c.contains("chattingui") ||
                    c.contains("conversationui") -> Category.WECHAT_CHAT
                else -> Category.WECHAT_CHAT // 未知微信界面，先当聊天处理，等待校准日志
            }
        }
        return Category.OTHER
    }
}
