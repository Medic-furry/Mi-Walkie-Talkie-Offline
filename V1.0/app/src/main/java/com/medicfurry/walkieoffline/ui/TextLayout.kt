package com.medicfurry.walkieoffline.ui

import android.widget.TextView

/**
 * 文案断行工具。
 *
 * Android 默认按「字符」断行，会把「故障」这类词拦腰截断，也可能留下单字行 / 行首标点。
 * 这里改成以「语义块」为最小不可拆单位，在布局完成后按实际可用宽度排版。
 * 块本身超宽时：英文按「词」填充（绝不拆单词），中文按「字」填充（每行至少 2 字）。
 */
object TextLayout {

    /**
     * 按可用宽度给 [tv] 断行。
     *
     * @param blocks 语义块（strings.xml 里用「|」分隔），块内绝不拆词。
     *               宽度在首次绘制时测量（tv.width 为 0 时用屏幕宽度估算兜底）。
     */
    fun wrapBlocks(tv: TextView, blocks: List<String>) {
        tv.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                tv.viewTreeObserver.removeOnPreDrawListener(this)
                var w = (tv.width - tv.paddingLeft - tv.paddingRight).toFloat()
                if (w <= 0f) {
                    // 还没完成布局（如 ScrollView 首测）：用屏幕宽度估算兜底
                    val dm = tv.resources.displayMetrics
                    w = dm.widthPixels * 0.68f
                }
                if (w > 0f) tv.text = layoutText(tv.paint, blocks, w)
                return true
            }
        })
    }

    private fun layoutText(p: android.text.TextPaint, blocks: List<String>, w: Float): String {
        val lines = ArrayList<StringBuilder>()
        var cur = StringBuilder()

        fun newLine() {
            if (cur.isNotEmpty()) {
                lines.add(cur)
                cur = StringBuilder()
            }
        }

        fun appendCharByChar(text: String) {
            for (ch in text) {
                if (cur.length >= 2 && p.measureText(cur.toString() + ch) > w) newLine()
                cur.append(ch)
            }
        }

        for (block in blocks) {
            val trimmed = block.trim()
            when {
                trimmed.isEmpty() -> {}

                p.measureText(trimmed) <= w -> {
                    // 整块放得下：放不下当前行就换行；英文块之间补空格，中文不加
                    val sep = if (needsSpace(cur, trimmed)) " " else ""
                    val fits = p.measureText(cur.toString() + sep + trimmed) <= w
                    if (cur.isNotEmpty() && !fits) {
                        newLine()
                        cur.append(trimmed)          // 换行后行首不加空格
                    } else {
                        cur.append(sep).append(trimmed)
                    }
                }

                trimmed.contains(' ') -> {
                    // 英文：按词填充，绝不拆单词
                    for (word in trimmed.split(Regex("\\s+"))) {
                        if (p.measureText(word) <= w) {
                            val candidate = if (cur.isEmpty()) word else "$cur $word"
                            if (p.measureText(candidate) > w) newLine()
                            if (cur.isNotEmpty()) cur.append(' ')
                            cur.append(word)
                        } else {
                            // 单词比一行还宽（极端）：只能硬拆
                            appendCharByChar(word)
                        }
                    }
                }

                else -> appendCharByChar(trimmed)   // 中文无空格：逐字填充
            }
        }
        if (cur.isNotEmpty()) lines.add(cur)

        // 后处理：消除单字行（把上一行末字挪下来）
        for (i in lines.indices.reversed()) {
            val line = lines[i]
            if (line.length == 1 && i > 0 && lines[i - 1].length > 1) {
                val prev = lines[i - 1]
                val moved = prev[prev.length - 1]
                prev.deleteCharAt(prev.length - 1)
                line.insert(0, moved)
            }
        }
        return lines.joinToString("\n")
    }

    /** 两个「拉丁词」相邻时补空格；中文/ CJK 字符不补 */
    private fun needsSpace(cur: StringBuilder, next: String): Boolean {
        if (cur.isEmpty() || next.isEmpty()) return false
        val a = cur.last()
        val b = next.first()
        return a.code < 0x2E80 && b.code < 0x2E80 &&
            a.isLetterOrDigit() && b.isLetterOrDigit()
    }
}
