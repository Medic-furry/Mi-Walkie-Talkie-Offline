package com.medicfurry.walkieoffline.ui

import android.content.Intent
import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.medicfurry.walkieoffline.MiWalkieApp
import com.medicfurry.walkieoffline.databinding.ActivityFirstRunBinding

/**
 * 首次启动流程：
 * 第一步 —— 刷写固件风险提示（含语言切换按钮）
 * 第二步 —— 法律风险提示（非业余频率）
 * 两步都需点击「已知」，仅首次显示。
 */
class FirstRunActivity : BaseActivity() {

    private lateinit var binding: ActivityFirstRunBinding
    private val prefs get() = (application as MiWalkieApp).prefs

    /** 当前处于第一步（风险提示）还是第二步（法律提示） */
    private var isLegalStep = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFirstRunBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 语言切换按钮
        binding.changeLanguageBtn.setOnClickListener { showLanguageDialog() }

        // 确认按钮：第一步→第二步；第二步→完成进入权限流程
        binding.confirmBtn.setOnClickListener {
            if (!isLegalStep) {
                // 切到第二步（法律风险）
                isLegalStep = true
                binding.titleText.setText(com.medicfurry.walkieoffline.R.string.first_run_legal_title)
                binding.messageText.setText(com.medicfurry.walkieoffline.R.string.first_run_legal_message)
                binding.confirmBtn.setText(com.medicfurry.walkieoffline.R.string.first_run_legal_confirm)
            } else {
                // 完成首次启动，进入权限申请
                prefs.isFirstRunDone = true
                startActivity(Intent(this, PermissionActivity::class.java))
                finish()
            }
        }
    }

    private fun showLanguageDialog() {
        val items = arrayOf(
            getString(com.medicfurry.walkieoffline.R.string.language_chinese),
            getString(com.medicfurry.walkieoffline.R.string.language_english)
        )
        val current = if (prefs.language == com.medicfurry.walkieoffline.data.PrefsManager.LANG_ZH) 0 else 1
        MaterialAlertDialogBuilder(this)
            .setTitle(com.medicfurry.walkieoffline.R.string.change_language)
            .setSingleChoiceItems(items, current) { dialog, which ->
                val lang = if (which == 0)
                    com.medicfurry.walkieoffline.data.PrefsManager.LANG_ZH
                else
                    com.medicfurry.walkieoffline.data.PrefsManager.LANG_EN
                prefs.language = lang
                dialog.dismiss()
                // 重建 Activity 使语言即时生效
                recreate()
            }
            .show()
    }
}
