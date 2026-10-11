package com.medicfurry.walkieoffline.ui

import android.os.Bundle
import android.text.method.LinkMovementMethod
import androidx.core.text.HtmlCompat
import com.medicfurry.walkieoffline.R
import com.medicfurry.walkieoffline.databinding.ActivityRiskDisclosureBinding

/**
 * 风险告知页：从「关于」页进入，纯展示首次启动时的两段风险/法律提示文本。
 * 只做展示，不要求用户点击「已知」，也不写任何标记位。
 */
class RiskDisclosureActivity : BaseActivity() {

    private lateinit var binding: ActivityRiskDisclosureBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRiskDisclosureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.title = getString(R.string.about_risk_disclosure)

        // 协议文本按 HTML 渲染：保留标题层级、加粗，链接可点击跳转
        binding.licenseText.text = HtmlCompat.fromHtml(
            getString(R.string.license_statement),
            HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        binding.licenseText.movementMethod = LinkMovementMethod.getInstance()
    }
}
