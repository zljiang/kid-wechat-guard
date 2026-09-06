package com.family.wxguard.ui

import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.family.wxguard.R
import com.family.wxguard.core.Cfg
import com.family.wxguard.core.PassResult

/**
 * 家长密码验证弹窗（统一入口，带错误重试上限与冷却，防孩子试密码）。
 *
 * @param onOk      验证通过后回调。
 * @param onBlocked 未设置密码以外的“不放行”出口：取消、触发冷却、或已处于冷却中。
 *                  对设置页/启动门一般传 finish()；对锁定页传 {}（保持锁定，不让取消）。
 */
object PassUi {

    fun showParentGate(
        activity: AppCompatActivity,
        onOk: () -> Unit,
        onBlocked: () -> Unit,
        allowCancel: Boolean = true,
        titleRes: Int = R.string.btn_unlock_pass
    ) {
        if (!Cfg.hasPasscode) {
            onOk()
            return
        }
        if (Cfg.passcodeLockRemainMs() > 0L) {
            lockedToast(activity)
            onBlocked()
            return
        }

        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = activity.getString(R.string.enter_pass)
        }

        val builder = AlertDialog.Builder(activity)
            .setTitle(titleRes)
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
        if (allowCancel) {
            builder.setNegativeButton(android.R.string.cancel) { _, _ -> onBlocked() }
        }

        val dialog = builder.create()
        dialog.setCancelable(false)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                when (Cfg.tryPasscode(input.text.toString())) {
                    PassResult.OK -> {
                        dialog.dismiss()
                        onOk()
                    }
                    PassResult.LOCKED -> {
                        lockedToast(activity)
                        dialog.dismiss()
                        onBlocked()
                    }
                    PassResult.WRONG -> {
                        val left = Cfg.passcodeAttemptsLeft()
                        if (left <= 0) {
                            lockedToast(activity)
                            dialog.dismiss()
                            onBlocked()
                        } else {
                            Toast.makeText(
                                activity,
                                activity.getString(R.string.pass_wrong_left, left),
                                Toast.LENGTH_SHORT
                            ).show()
                            input.text?.clear()
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun lockedToast(activity: AppCompatActivity) {
        val sec = (Cfg.passcodeLockRemainMs() + 999) / 1000
        Toast.makeText(
            activity,
            activity.getString(R.string.pass_locked, sec),
            Toast.LENGTH_SHORT
        ).show()
    }
}
