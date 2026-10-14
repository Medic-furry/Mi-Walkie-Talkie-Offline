package com.medicfurry.walkieoffline.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

/**
 * 支持双指缩放、单指拖动、双击放大/还原的 ImageView（彩蛋页查看图片用）。
 * 1x = 图片适配屏幕居中；双指捏合可在 1x ~ 6x 间缩放，拖动范围钳制在图片边界内。
 */
@SuppressLint("ClickableViewAccessibility")
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    /** 当前显示变换（缩放 + 平移），直接作为 imageMatrix 应用到 ImageView */
    private val matrix = Matrix()
    /** 让图片刚好铺满控件的缩放值，1x 的基准 */
    private var baseScale = 1f
    /** 相对 baseScale 的倍数，取值 1x ~ 6x */
    private var scale = 1f
    /** 是否已经完成布局（尺寸为 0 时无法计算缩放） */
    private var laidOut = false

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean = true

            override fun onScale(d: ScaleGestureDetector): Boolean {
                val newScale = (scale * d.scaleFactor).coerceIn(1f, MAX_SCALE)
                val applied = newScale / scale
                if (applied != 1f) {
                    matrix.postScale(applied, applied, d.focusX, d.focusY)
                    scale = newScale
                    clampAndApply()
                }
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                dx: Float,
                dy: Float
            ): Boolean {
                if (scaleDetector.isInProgress) return false
                matrix.postTranslate(-dx, -dy)
                clampAndApply()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scale > 1.01f) reset() else zoomTo(3f, e.x, e.y)
                return true
            }
        })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            laidOut = true
            reset()
        }
    }

    /** 复位：回到 1x 并居中显示 */
    private fun reset() {
        val d = drawable ?: return
        if (!laidOut || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        baseScale = min(width / dw, height / dh)
        scale = 1f
        matrix.setScale(baseScale, baseScale)
        centerImage()
        imageMatrix = matrix
    }

    /** 以 (x, y) 为锚点缩放到 target 倍（双击放大用） */
    private fun zoomTo(target: Float, x: Float, y: Float) {
        val applied = target / scale
        matrix.postScale(applied, applied, x, y)
        scale = target
        clampAndApply()
    }

    /** 图片居中（复位时用） */
    private fun centerImage() {
        val d = drawable ?: return
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        matrix.mapRect(r)
        matrix.postTranslate(width / 2f - r.centerX(), height / 2f - r.centerY())
    }

    /** 拖动/缩放后把图片钳制在可视范围内：能铺满时居中，铺不满时贴边不露底 */
    private fun clampAndApply() {
        val d = drawable ?: return
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        matrix.mapRect(r)
        val vw = width.toFloat()
        val vh = height.toFloat()
        var dx = 0f
        var dy = 0f
        if (r.width() <= vw) dx = vw / 2f - r.centerX()
        else if (r.left > 0f) dx = -r.left
        else if (r.right < vw) dx = vw - r.right
        if (r.height() <= vh) dy = vh / 2f - r.centerY()
        else if (r.top > 0f) dy = -r.top
        else if (r.bottom < vh) dy = vh - r.bottom
        if (dx != 0f || dy != 0f) matrix.postTranslate(dx, dy)
        imageMatrix = matrix
    }

    /** 手势入口：先给缩放探测器（双指），再给手势探测器（拖动 / 双击） */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    companion object {
        private const val MAX_SCALE = 6f
    }
}
