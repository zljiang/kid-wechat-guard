package com.family.wxguard.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.family.wxguard.R
import com.family.wxguard.core.Cfg
import com.family.wxguard.core.InnerPolicy
import com.family.wxguard.domain.Category

/**
 * 策略与配额设置页。未设过家长密码时直接进入；已设则先验证密码。
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PassUi.showParentGate(this, onOk = { initUi() }, onBlocked = { finish() })
    }

    private fun initUi() {
        setContentView(R.layout.activity_settings)
        bindViews()
    }

    private fun bindViews() {
        // 小程序/小游戏
        val groupMini = findViewById<RadioGroup>(R.id.groupMiniPolicy)
        groupMini.check(
            if (Cfg.policyFor(Category.MINI_APP) == InnerPolicy.QUOTA)
                R.id.radioMiniQuota else R.id.radioMiniBlock
        )
        findViewById<EditText>(R.id.edMiniWeekday).setText(Cfg.quotaMinutes(Category.MINI_APP, false).toString())
        findViewById<EditText>(R.id.edMiniWeekend).setText(Cfg.quotaMinutes(Category.MINI_APP, true).toString())

        // 视频号
        val groupChannels = findViewById<RadioGroup>(R.id.groupChannelsPolicy)
        groupChannels.check(
            if (Cfg.policyFor(Category.CHANNELS) == InnerPolicy.QUOTA)
                R.id.radioChannelsQuota else R.id.radioChannelsBlock
        )
        findViewById<EditText>(R.id.edChannelsWeekday).setText(Cfg.quotaMinutes(Category.CHANNELS, false).toString())
        findViewById<EditText>(R.id.edChannelsWeekend).setText(Cfg.quotaMinutes(Category.CHANNELS, true).toString())

        findViewById<EditText>(R.id.edGrace).setText(Cfg.graceMinutes().toString())

        findViewById<Button>(R.id.btnChangePass).setOnClickListener { changePasscode() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveAll() }
    }

    private fun saveAll() {
        val miniPolicy =
            if (findViewById<RadioGroup>(R.id.groupMiniPolicy).checkedRadioButtonId == R.id.radioMiniQuota)
                InnerPolicy.QUOTA else InnerPolicy.BLOCK
        Cfg.setPolicy(Category.MINI_APP, miniPolicy)

        val channelPolicy =
            if (findViewById<RadioGroup>(R.id.groupChannelsPolicy).checkedRadioButtonId == R.id.radioChannelsQuota)
                InnerPolicy.QUOTA else InnerPolicy.BLOCK
        Cfg.setPolicy(Category.CHANNELS, channelPolicy)

        Cfg.setQuotaMinutes(Category.MINI_APP, false, num(R.id.edMiniWeekday, 30))
        Cfg.setQuotaMinutes(Category.MINI_APP, true, num(R.id.edMiniWeekend, 60))
        Cfg.setQuotaMinutes(Category.CHANNELS, false, num(R.id.edChannelsWeekday, 15))
        Cfg.setQuotaMinutes(Category.CHANNELS, true, num(R.id.edChannelsWeekend, 30))
        Cfg.setGraceMinutes(num(R.id.edGrace, 5))

        Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
    }

    private fun num(id: Int, fallback: Int): Int =
        findViewById<EditText>(id).text.toString().toIntOrNull() ?: fallback

    private fun changePasscode() {
        val p1 = findViewById<EditText>(R.id.edPassNew).text.toString()
        val p2 = findViewById<EditText>(R.id.edPassNew2).text.toString()
        if (p1.length < 4) {
            Toast.makeText(this, R.string.toast_pass_required, Toast.LENGTH_SHORT).show()
            return
        }
        if (p1 != p2) {
            Toast.makeText(this, R.string.toast_pass_mismatch, Toast.LENGTH_SHORT).show()
            return
        }
        Cfg.setPasscode(p1)
        findViewById<EditText>(R.id.edPassNew).text.clear()
        findViewById<EditText>(R.id.edPassNew2).text.clear()
        Toast.makeText(this, R.string.toast_pass_changed, Toast.LENGTH_SHORT).show()
    }
}
