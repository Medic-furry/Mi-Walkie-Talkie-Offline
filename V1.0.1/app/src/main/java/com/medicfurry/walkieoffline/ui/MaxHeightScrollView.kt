package com.medicfurry.walkieoffline.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ScrollView

/**
 * 带最大高度限制的 ScrollView：
 * 内容短时按内容高度显示，内容超长（如小分辨率下的英文警告）时滚动，
 * 保证弹窗永远不会被顶出屏幕。
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    /** 内容最大高度（px），默认不限制 */
    var maxHeightPx: Int = Int.MAX_VALUE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val capped = View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, capped)
    }
}
