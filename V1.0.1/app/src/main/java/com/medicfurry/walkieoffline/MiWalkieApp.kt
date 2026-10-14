package com.medicfurry.walkieoffline

import android.app.Application
import com.medicfurry.walkieoffline.data.PrefsManager

/**
 * 应用入口：持有全局唯一的 [PrefsManager]（语言偏好、首启标记等）。
 *
 * 语言切换链路：PrefsManager.language → BaseActivity.attachBaseContext 应用 Locale
 * → 新建/重建的页面立即使用新语言，无需重启 App。
 */
class MiWalkieApp : Application() {

    /** 全局偏好；外部只读，写入通过 PrefsManager 自身的接口 */
    lateinit var prefs: PrefsManager
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = PrefsManager(this)
    }
}
