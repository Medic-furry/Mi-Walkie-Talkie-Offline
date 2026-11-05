package com.medicfurry.walkieoffline.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.medicfurry.walkieoffline.BuildConfig
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.medicfurry.walkieoffline.MiWalkieApp
import com.medicfurry.walkieoffline.R
import com.medicfurry.walkieoffline.data.PrefsManager
import com.medicfurry.walkieoffline.databinding.ActivityAboutBinding

/**
 * 关于页：显示版本号 + 跳转 GitHub 项目主页。
 * 隐藏开关：连续点击版本号 5 次 → 切换「刷机确认 10 秒倒计时」的开关。
 */
class AboutActivity : BaseActivity() {

    private lateinit var binding: ActivityAboutBinding

    private var versionTapCount = 0
    private val versionTapReset = Runnable { versionTapCount = 0 }
    private val versionTapHandler = Handler(Looper.getMainLooper())

    companion object {
        const val GITHUB_URL = "https://github.com/Medic-furry/Mi-Walkie-Talkie-Offline"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.title = getString(R.string.about_title)
        binding.appVersion.text = "小米对讲离线 v${BuildConfig.VERSION_NAME}"

        binding.githubBtn.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))
            startActivity(intent)
        }

        // 风险告知：打开纯展示页，显示首次启动的两段风险/法律提示，不需要点确认
        binding.riskBtn.setOnClickListener {
            startActivity(Intent(this, RiskDisclosureActivity::class.java))
        }

        // 语言切换按钮：在 中文 / English 之间来回切，切换后重启应用全局生效
        val prefs = (application as MiWalkieApp).prefs
        val isZh = prefs.language == PrefsManager.LANG_ZH
        val targetName = getString(
            if (isZh) R.string.language_english else R.string.language_chinese
        )
        binding.langBtn.text = "${getString(R.string.change_language)} → $targetName"
        // MaterialButton 只认自己的 icon API，android:drawableStart / drawableStartCompat 都不显示
        // 关于页的语言按钮：图标保持白色 tint（用户要求，不能换），按钮背景不动
        (binding.langBtn as? com.google.android.material.button.MaterialButton)?.apply {
            setIconResource(R.drawable.ic_translate)
            iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = (10 * resources.displayMetrics.density).toInt()
            iconTint = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
        }
        binding.langBtn.setOnClickListener {
            prefs.language = if (isZh) PrefsManager.LANG_EN else PrefsManager.LANG_ZH
            Toast.makeText(this, R.string.about_language_switched, Toast.LENGTH_SHORT).show()
            // 热切换：本页立即重建生效，返回其它页面时由 BaseActivity.onResume 自动重建
            recreate()
        }

        // MOD固件频道：手动指定频道数量（仅限固件开发使用）。
        // 设置后 App 不再看固件版本指纹，频道容量直接使用该值；清空 = 跟随固件自动判定。
        binding.modChannelBtn.setOnClickListener { showModChannelDialog() }

        // 隐藏开关：连续 5 次点击版本号（间隔超过 2 秒则重新计数）
        binding.appVersion.setOnClickListener {
            versionTapHandler.removeCallbacks(versionTapReset)
            versionTapHandler.postDelayed(versionTapReset, 2000)
            versionTapCount++
            if (versionTapCount >= 5) {
                versionTapCount = 0
                versionTapHandler.removeCallbacks(versionTapReset)
                val prefs = (application as MiWalkieApp).prefs
                val nowOff = !prefs.bypassFlashCountdown
                prefs.bypassFlashCountdown = nowOff
                Toast.makeText(
                    this,
                    if (nowOff) R.string.about_countdown_off else R.string.about_countdown_on,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showModChannelDialog() {
        val prefs = (application as MiWalkieApp).prefs
        val current = prefs.modChannelSlots
        val currentText = if (current > 0)
            getString(R.string.mod_channel_current_manual, current)
        else getString(R.string.mod_channel_current_auto)
        // 第一步：警告（仅限固件开发使用）
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_mod_channel)
            .setMessage(getString(R.string.mod_channel_warning) + "\n\n" + currentText)
            .setPositiveButton(R.string.mod_channel_set) { _, _ -> inputModChannelCount() }
            .setNeutralButton(R.string.mod_channel_follow) { _, _ ->
                prefs.modChannelSlots = 0
                Toast.makeText(this, R.string.mod_channel_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun inputModChannelCount() {
        val input = EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        input.hint = getString(R.string.mod_channel_input_hint)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        container.addView(input)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_mod_channel)
            .setView(container)
            .setPositiveButton(R.string.done) { _, _ ->
                val prefs = (application as MiWalkieApp).prefs
                val v = input.text.toString().trim()
                val n = v.toIntOrNull() ?: 0
                if (n in 20..200) {
                    prefs.modChannelSlots = n
                    Toast.makeText(this,
                        getString(R.string.mod_channel_set_done, n), Toast.LENGTH_LONG).show()
                } else {
                    prefs.modChannelSlots = 0
                    Toast.makeText(this, R.string.mod_channel_cleared, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        versionTapHandler.removeCallbacks(versionTapReset)
    }
}
