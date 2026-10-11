package com.medicfurry.walkieoffline.protocol

/**
 * 亚音（CTCSS/DCS）表，依据实测：
 * - 模拟亚音数组 res/values shark_analog_tone（51 项：[0]=关闭 + 50 个标准 CTCSS）
 * - 数字亚音 DCS 104 码（shark_digital_tone_normal / _inversion，各 105 项：[0]=关闭）
 * - css int → 显示串的映射规则（设备端 i/c0.h(I)）：
 *     css < 0x100      → analog[css]        （css 即数组下标）
 *     css = 0x200 + i  → digitalNormal[i]   （i 为数组下标，[0]=关闭）
 *     css = 0x300 + i  → digitalInversion[i]
 */
object CssTones {

    /** 模拟亚音（设备端 shark_analog_tone，index 0 = 关闭） */
    val ANALOG = listOf(
        "关闭",
        "67.0 Hz", "69.3 Hz", "71.9 Hz", "74.4 Hz", "77.0 Hz", "79.7 Hz", "82.5 Hz",
        "85.4 Hz", "88.5 Hz", "91.5 Hz", "94.8 Hz", "97.4 Hz", "100.0 Hz", "103.5 Hz",
        "107.2 Hz", "110.9 Hz", "114.8 Hz", "118.8 Hz", "123.0 Hz", "127.3 Hz", "131.8 Hz",
        "136.5 Hz", "141.3 Hz", "146.2 Hz", "151.4 Hz", "156.7 Hz", "159.8 Hz", "162.2 Hz",
        "165.5 Hz", "167.9 Hz", "171.3 Hz", "173.8 Hz", "177.3 Hz", "179.9 Hz", "183.5 Hz",
        "186.2 Hz", "189.9 Hz", "192.8 Hz", "196.6 Hz", "199.5 Hz", "203.5 Hz", "206.5 Hz",
        "210.7 Hz", "218.1 Hz", "225.7 Hz", "229.1 Hz", "233.6 Hz", "241.8 Hz", "250.3 Hz",
        "254.1 Hz"
    )

    /** DCS 104 码（设备端 shark_digital_tone_normal/_inversion 共用码表，index 1..104） */
    private val DCS_CODES = listOf(
        "023", "025", "026", "031", "032", "036", "043", "047", "051", "053", "054",
        "065", "071", "072", "073", "074", "114", "115", "116", "122", "125", "131",
        "132", "134", "143", "145", "152", "155", "156", "162", "165", "172", "174",
        "205", "212", "223", "225", "226", "243", "244", "245", "246", "251", "252",
        "255", "261", "263", "265", "266", "271", "274", "306", "311", "315", "325",
        "331", "332", "343", "346", "351", "356", "364", "365", "371", "411", "412",
        "413", "423", "431", "432", "445", "446", "452", "454", "455", "462", "464",
        "465", "466", "503", "506", "516", "523", "526", "532", "546", "565", "606",
        "612", "624", "627", "631", "632", "654", "662", "664", "703", "712", "723",
        "731", "732", "734", "743", "754"
    )

    val DIGITAL_NORMAL: List<String> = listOf("关闭") + DCS_CODES.map { "D${it}N" }
    val DIGITAL_INVERSION: List<String> = listOf("关闭") + DCS_CODES.map { "D${it}I" }

    /** css int → 显示串（与设备端显示一致） */
    fun toDisplay(css: Int): String = when {
        css < 0 -> ANALOG[0]
        css < 0x100 -> ANALOG.getOrElse(css) { ANALOG[0] }
        css < 0x200 -> ANALOG.getOrElse(css - 0x100) { ANALOG[0] }   // 设备端 hi==1 也映射 analog
        css < 0x300 -> DIGITAL_NORMAL.getOrElse(css - 0x200) { ANALOG[0] }
        else -> DIGITAL_INVERSION.getOrElse(css - 0x300) { ANALOG[0] }
    }

    /** 选择器用的完整条目列表：显示串 → css 值（模拟 51 + 数字N 104 + 数字I 104，关闭只出现一次） */
    val pickerEntries: List<Pair<String, Int>> by lazy {
        val list = mutableListOf<Pair<String, Int>>()
        ANALOG.forEachIndexed { i, s -> list.add(s to i) }                       // css 0..50
        DCS_CODES.forEachIndexed { i, c -> list.add("D${c}N" to (0x200 + i + 1)) }
        DCS_CODES.forEachIndexed { i, c -> list.add("D${c}I" to (0x300 + i + 1)) }
        list
    }
}
