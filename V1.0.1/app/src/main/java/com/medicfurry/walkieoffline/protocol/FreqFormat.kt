package com.medicfurry.walkieoffline.protocol

/**
 * 频率格式化（对齐 格式化逻辑 的 b(I)Ljava/lang/String;）。
 *
 * 设备端算法：频率 int 单位 Hz。
 *   frac = hz % 1000000 + 1000000   （补 1 开头占位）
 *   去掉最多 2 个尾零
 *   整数部分 = hz / 1000000
 *   StringBuilder: append(整数) → 记录长度 pos → append(frac) → setCharAt(pos, '.')
 *
 * 验证（与设备端 APP 截图一致）：
 *   146500000 → frac=1500000→150000→15000 → "146"+"15000" → "146.5000"
 *   438712500 → frac=1712500→171250→17125 → "438"+"17125" → "438.17125"
 *   409750000 → frac=1750000→175000→17500 → "409"+"17500" → "409.7500"
 */
object FreqFormat {

    fun format(hz: Int): String {
        if (hz <= 0) return "--"
        val million = 1000000

        var frac = hz % million + million
        if (frac % 10 == 0) frac /= 10
        if (frac % 10 == 0) frac /= 10

        val intPart = hz / million

        val sb = StringBuilder()
        sb.append(intPart)
        val dotPos = sb.length
        sb.append(frac)
        sb.setCharAt(dotPos, '.')
        return sb.toString()
    }
}
