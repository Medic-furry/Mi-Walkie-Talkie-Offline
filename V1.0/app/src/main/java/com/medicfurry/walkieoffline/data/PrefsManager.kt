package com.medicfurry.walkieoffline.data

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/**
 * 应用偏好管理：
 * - 首次启动流程标记（风险提示 + 法律提示，仅首次显示）
 * - 语言切换（中英双语）
 */
class PrefsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("mi_walkie_offline_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_FIRST_RUN_DONE = "first_run_done"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_LAST_VER_HW = "last_ver_hw"
        private const val KEY_BYPASS_FLASH_COUNTDOWN = "bypass_flash_countdown"
        const val LANG_ZH = "zh"
        const val LANG_EN = "en"
    }

    /** 是否已完成首次启动流程（两个风险提示都点过「已知」） */
    var isFirstRunDone: Boolean
        get() = prefs.getBoolean(KEY_FIRST_RUN_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_FIRST_RUN_DONE, value).apply()

    /** 当前语言（zh / en） */
    var language: String
        get() = prefs.getString(KEY_LANGUAGE, LANG_ZH) ?: LANG_ZH
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    /** 最近一次从设备读到的硬件版本号（verHw）。
     *  设备端把它存在 设备硬件版本，刷固件时用
     *  "_hw" + verHw + ".bin" 过滤目录；设备离线时也能沿用上次的值。 */
    var lastVerHw: Int
        get() = prefs.getInt(KEY_LAST_VER_HW, -1)
        set(value) = prefs.edit().putInt(KEY_LAST_VER_HW, value).apply()

    /** 隐藏开关：刷机确认弹窗的 10 秒倒计时是否已关闭。
     *  入口在「关于」页——连续点击版本号 5 次切换。关闭后点「刷写」时
     *  「确认」按钮立即可点（警告弹窗仍然保留，不会跳过确认这一步）。 */
    var bypassFlashCountdown: Boolean
        get() = prefs.getBoolean(KEY_BYPASS_FLASH_COUNTDOWN, false)
        set(value) = prefs.edit().putBoolean(KEY_BYPASS_FLASH_COUNTDOWN, value).apply()

    /** 应用语言到当前 Activity 上下文（用于语言切换后即时生效） */
    fun applyLocale(context: Context): Context {
        val locale = if (language == LANG_EN) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE
        Locale.setDefault(locale)
        val config = context.resources.configuration
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
