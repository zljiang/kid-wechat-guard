package com.family.wxguard.ui

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.family.wxguard.R
import com.family.wxguard.core.ProcessState
import com.family.wxguard.guard.GuardEngine

/**
 * 全屏锁定页：配额耗尽或命中“禁止”策略时由 [GuardEngine] 拉起。
 * 两个出口：返回微信聊天（代按返回键退出内层）/ 家长密码解锁（本会话放行）。
 */
class LockActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock)

        val category = ProcessState.lockCategory
        val reason = ProcessState.lockReason

        findViewById<TextView>(R.id.lockCategoryText).text = category.label
        findViewById<TextView>(R.id.lockReasonText).text = reason

        findViewById<Button>(R.id.btnBackChat).setOnClickListener {
            GuardEngine.backToChat()
            // 真正退出内层容器时，onForegroundChanged 会触发 dismissLock 关闭本页
        }
        findViewById<Button>(R.id.btnUnlock).setOnClickListener {
            promptParent()
        }
    }

    override fun onResume() {
        super.onResume()
        ProcessState.registerActivity(this)
    }

    override fun onStop() {
        super.onStop()
        ProcessState.unregisterActivity(this)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 屏蔽返回键，防止直接退出锁定页；只能走页内两个按钮。
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(event)
    }

    private fun promptParent() {
        // 锁定页不允许取消/跳过；错误会触发冷却，但页面保持，冷却后可重试。
        PassUi.showParentGate(
            this,
            allowCancel = false,
            onOk = {
                GuardEngine.unlockByParent()
                Toast.makeText(this, R.string.toast_unlocked_session, Toast.LENGTH_SHORT).show()
                finish()
            },
            onBlocked = {}
        )
    }
}
