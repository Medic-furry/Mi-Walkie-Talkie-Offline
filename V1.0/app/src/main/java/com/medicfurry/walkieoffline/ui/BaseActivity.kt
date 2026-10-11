package com.medicfurry.walkieoffline.ui

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.medicfurry.walkieoffline.MiWalkieApp

/**
 * 所有 Activity 的基类。
 * - attachBaseContext 中应用语言偏好，使新建的页面直接用当前语言；
 * - onResume 里检查语言是否已被别处热切换，若不同则自动重建本页，
 *   这样在「关于」页切换语言后，返回其它页面也能即时生效（无需重启 App）。
 */
abstract class BaseActivity : AppCompatActivity() {

    private val prefs get() = (application as MiWalkieApp).prefs

    /** 本实例创建时应用的语言 */
    private var appliedLanguage: String? = null

    override fun attachBaseContext(newBase: Context) {
        val p = (newBase.applicationContext as? MiWalkieApp)?.prefs
        appliedLanguage = p?.language
        val context = p?.applyLocale(newBase) ?: newBase
        super.attachBaseContext(context)
    }

    override fun onResume() {
        super.onResume()
        val current = runCatching { prefs.language }.getOrNull()
        if (current != null && appliedLanguage != null && current != appliedLanguage) {
            recreate()          // 语言被热切换过 → 重建本页
        }
    }
}
