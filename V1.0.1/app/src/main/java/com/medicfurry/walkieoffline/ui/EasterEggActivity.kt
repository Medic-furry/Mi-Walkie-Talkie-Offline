package com.medicfurry.walkieoffline.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import com.medicfurry.walkieoffline.databinding.ActivityEasterEggBinding

/**
 * 彩蛋页：主页「调试终端」标题连续点击 5 次进入。
 * 展示项目形象图（双指缩放 / 单指拖动 / 双击放大还原），下方为 GitHub 项目主页按钮。
 */
class EasterEggActivity : BaseActivity() {

    private lateinit var binding: ActivityEasterEggBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEasterEggBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadOriginalImage()
        binding.githubBtn.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AboutActivity.GITHUB_URL)))
        }
    }

    /**
     * 从 assets 加载原图，保证不被压缩/降色：
     * - inScaled=false：不按屏幕密度缩放
     * - inSampleSize=1：不做降采样，按原始 1672×941 解码
     * - ARGB_8888：保留完整色彩，避免降成 RGB_565 出现色带
     */
    private fun loadOriginalImage() {
        val bitmap = runCatching {
            assets.open("easter_egg.jpg").use { input ->
                val opts = BitmapFactory.Options().apply {
                    inScaled = false
                    inSampleSize = 1
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeStream(input, null, opts)
            }
        }.getOrNull()
        if (bitmap != null) {
            binding.eggImage.setImageBitmap(bitmap)
        }
    }
}
