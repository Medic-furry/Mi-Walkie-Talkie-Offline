package com.medicfurry.walkieoffline.protocol

import java.io.ByteArrayOutputStream

/**
 * Seal（小米对讲机 1S）protobuf 消息编解码。
 *
 * 字段编号依据设备实测通信分析确定（与设备端一致，勿改）：
 * - SEAL_Connect: version=1, connCode=2, deviceId=3, deviceType=4, appMode=5, language=6, color=7
 * - SEAL_ChannelInfo: ch_no=1, ch_type=2, ch_txpower=3, ch_rxfreq=4, ch_txfreq=5,
 *                     ch_name=6, ch_sq=7, ch_vox=8, ch_rxcss=9, ch_txcss=10,
 *                     ch_elim=11, ch_band=12, ch_polite=13
 * - SEAL_ChannelInfoOperate: version=1, result=2, option=3, ch=4
 */
object SealProtos {

    // ---------- 枚举值（与设备端一致） ----------

    object ConnCode {
        const val DISCONNECT = 0
        const val CONNECT = 1        // app → 设备：请求连接
        const val ALLOW = 4          // 设备 → app：允许
        const val REFUSE = 5
        const val WAIT = 6           // 设备 → app：等待对讲机屏幕确认
        const val LOWPOWER = 7
        const val BUSY = 12
    }

    object AppType {
        const val OWN_APP = 0
        const val MIJIA_APP = 1
    }

    object ChOption {
        const val INSERT = 0
        const val DELETE = 1
        const val MODIFY = 2
        const val QUERY = 3
    }

    object ChType {
        const val PUBLIC = 0
        const val REMOTE = 1
        const val USERDEF = 2
    }

    /** SEAL/SHARK_CH_UERR（与设备端一致） */
    object ChResult {
        const val OK = 0
        const val ERROR = 1
        const val EMPTY = 2            // 信道列表为空 / 拉取到末尾
        const val FORBIDE = 3
        const val QUERY_CONTINUE = 4   // 查询响应：后面还有
        const val QUERY_FINISH = 5     // 查询响应：最后一帧
    }

    // ---------- SEAL_Connect ----------

    class Connect(
        var version: Int = 1,
        var connCode: Int = ConnCode.CONNECT,
        var deviceId: Int = 0,
        var deviceType: Int = 5,        // Seal = 5（设备端 f.a(config) 固定值）
        var appMode: Int = AppType.OWN_APP
    )

    fun encodeConnect(c: Connect): ByteArray {
        val out = ByteArrayOutputStream()
        ProtoIO.writeIntField(out, 1, c.version)
        ProtoIO.writeIntField(out, 2, c.connCode)
        if (c.deviceId != 0) ProtoIO.writeIntField(out, 3, c.deviceId)
        ProtoIO.writeIntField(out, 4, c.deviceType)
        ProtoIO.writeIntField(out, 5, c.appMode)
        return out.toByteArray()
    }

    fun decodeConnect(data: ByteArray): Connect {
        val c = Connect()
        ProtoIO.forEachField(data) { field, wire, r ->
            when (field) {
                1 -> c.version = r.readVarint().toInt()
                2 -> c.connCode = r.readVarint().toInt()
                3 -> c.deviceId = r.readVarint().toInt()
                4 -> c.deviceType = r.readVarint().toInt()
                5 -> c.appMode = r.readVarint().toInt()
                else -> r.skipField(wire)
            }
        }
        return c
    }

    // ---------- SEAL_ChannelInfo ----------

    class ChannelInfo {
        var no: Int = -1            // 信道号
        var type: Int = -1          // CHTYPE: 0=PUBLIC 1=REMOTE 2=USERDEF
        var txPower: Int = -1
        var rxFreq: Int = -1        // 接收频率 Hz
        var txFreq: Int = -1        // 发射频率 Hz
        var name: String = ""       // GB2312 信道名
        var sq: Int = -1
        var vox: Int = -1
        var rxCss: Int = -1         // 接收亚音（field 9，0=关闭）
        var txCss: Int = -1         // 发射亚音（field 10，0=关闭）

        val isValid: Boolean get() = rxFreq > 0
    }

    fun encodeChannelInfo(ch: ChannelInfo): ByteArray {
        val out = ByteArrayOutputStream()
        if (ch.no >= 0) ProtoIO.writeIntField(out, 1, ch.no)
        if (ch.type >= 0) ProtoIO.writeIntField(out, 2, ch.type)
        if (ch.txPower >= 0) ProtoIO.writeIntField(out, 3, ch.txPower)
        if (ch.rxFreq > 0) ProtoIO.writeIntField(out, 4, ch.rxFreq)
        if (ch.txFreq > 0) ProtoIO.writeIntField(out, 5, ch.txFreq)
        if (ch.name.isNotEmpty()) {
            ProtoIO.writeBytesField(out, 6, ch.name.toByteArray(charset("GB2312")))
        }
        if (ch.sq >= 0) ProtoIO.writeIntField(out, 7, ch.sq)
        if (ch.vox >= 0) ProtoIO.writeIntField(out, 8, ch.vox)
        if (ch.rxCss >= 0) ProtoIO.writeIntField(out, 9, ch.rxCss)
        if (ch.txCss >= 0) ProtoIO.writeIntField(out, 10, ch.txCss)
        return out.toByteArray()
    }

    fun decodeChannelInfo(data: ByteArray): ChannelInfo {
        val ch = ChannelInfo()
        ProtoIO.forEachField(data) { field, wire, r ->
            when (field) {
                1 -> ch.no = r.readVarint().toInt()
                2 -> ch.type = r.readVarint().toInt()
                3 -> ch.txPower = r.readVarint().toInt()
                4 -> ch.rxFreq = r.readVarint().toInt()
                5 -> ch.txFreq = r.readVarint().toInt()
                6 -> ch.name = runCatching {
                    String(r.readBytes(), charset("GB2312"))
                }.getOrDefault("")
                7 -> ch.sq = r.readVarint().toInt()
                8 -> ch.vox = r.readVarint().toInt()
                9 -> ch.rxCss = r.readVarint().toInt()
                10 -> ch.txCss = r.readVarint().toInt()
                else -> r.skipField(wire)
            }
        }
        return ch
    }

    // ---------- SHARK_UpGradeFile*（固件升级消息）----------
    //
    // proto 定义（按实测还原，字段编号来自 设备端实现 的 FileDescriptorProto 常量串）：
    //   message SHARK_UpGradeFileRequest {
    //     required uint32 version = 1;
    //     required SHARK_FILECMD  fCmd  = 2;
    //     required SHARK_FILETYPE fType = 3;
    //     optional uint32 fSize  = 4;
    //     optional uint32 fCrc32 = 5;
    //   }
    //   enum SHARK_FILECMD  { SHARK_UP_START=1; SHARK_UP_COMPLE=2; SHARK_UP_REBOOT=3; }
    //   enum SHARK_FILETYPE { SHARK_APP_UPGRADE_FILE=1; SHARK_MCU_UPLOAD_FILE=2; }
    //   message SHARK_UpGradeFileResponse {
    //     required SHARK_UP_UERR result = 1;
    //     optional uint32 offset = 2;
    //   }
    //   enum SHARK_UP_UERR { SHARK_UP_RESULT_OK=1; SHARK_UP_RESULT_ERROR=2; SHARK_UP_RESULT_LOW_POWER=3; }
    //   message SHARK_UpGradeFilePack { required uint32 offset=1; required bytes data=2; required uint32 xor=3; }

    object UpgradeCmd {                 // SHARK_FILECMD
        const val START = 1
        const val COMPLE = 2
        const val REBOOT = 3
    }

    object UpgradeType {                // SHARK_FILETYPE
        const val APP_UPGRADE_FILE = 1
        const val MCU_UPLOAD_FILE = 2
    }

    object UpgradeResult {              // SHARK_UP_UERR
        const val OK = 1
        const val ERROR = 2
        const val LOW_POWER = 3
    }

    /** 升级控制命令（设备端实现：START/COMPLE/REBOOT 三条）。
     *  只有 COMPLE 才带 fSize + fCrc32（设备端实现）。 */
    fun encodeUpgradeRequest(fCmd: Int, fSize: Int = 0, fCrc32: Int = 0): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ProtoIO.writeIntField(out, 1, 1)                       // version = 1
        ProtoIO.writeIntField(out, 2, fCmd)
        ProtoIO.writeIntField(out, 3, UpgradeType.APP_UPGRADE_FILE)
        if (fCmd == UpgradeCmd.COMPLE) {
            // fSize/fCrc32 都是 uint32，必须按无符号 32 位编码（设备端 writeRawVarint32）
            ProtoIO.writeUInt32Field(out, 4, fSize)
            ProtoIO.writeUInt32Field(out, 5, fCrc32)
        }
        return out.toByteArray()
    }

    /** 升级数据包（设备端实现）：offset + data + 逐字节 XOR（初值 0） */
    fun encodeUpgradePack(offset: Int, chunk: ByteArray, xor: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ProtoIO.writeIntField(out, 1, offset)
        ProtoIO.writeBytesField(out, 2, chunk)
        ProtoIO.writeIntField(out, 3, xor)
        return out.toByteArray()
    }

    class UpgradeResponse {
        var result: Int = -1
        var offset: Int = -1
    }

    fun decodeUpgradeResponse(data: ByteArray): UpgradeResponse {
        val resp = UpgradeResponse()
        ProtoIO.forEachField(data) { field, wireType, r ->
            when (field) {
                1 -> resp.result = r.readVarint().toInt()
                2 -> resp.offset = r.readVarint().toInt()
                else -> r.skipField(wireType)
            }
        }
        return resp
    }

    // ---------- SEAL/SHARK_DeviceParam（设备参数，字段编号 Seal/Shark 一致） ----------
    //
    // 字段编号（依据设备实测通信分析确定）：version=1, result=2, fullInquiry=3, verHw=4, verSoft=5,
    //   deviceName=6(GB2312), bleName=7(UTF-8), charge=8, activate=9, shareLoc=10,
    //   btAudio=11, ch=12(嵌套 ChannelInfo), voiceAlert=13, isAllPublic=14,
    //   pwSaveMode=15, voiceType=16, [Shark 独有] timeStamp=30, timeDisplay=31
    //
    // result 枚举（SEAL/SHARK_DEV_UERR）：
    //   QUERY_OK=1, QUERY_ERROR=2, UPDATE_OK=3, UPDATE_ERROR=4

    object ParamResult {
        const val QUERY_OK = 1
        const val QUERY_ERROR = 2
        const val UPDATE_OK = 3
        const val UPDATE_ERROR = 4
    }

    class DeviceParam {
        var version: Int = 0
        var result: Int = 0
        var verHw: Int = -1
        var verSoft: Int = -1
        var deviceName: String = ""      // GB2312 解码
        var bleName: String = ""         // UTF-8 解码
        var charge: Int = -1             // 电量
        var txPower: Int = -1            // 嵌套 ch.chTxPower（Shark 功率等级 1=低 2=高）
        var timeDisplay: Int = -1        // Shark：屏幕时间显示开关
        var voiceType: Int = -1
        var voiceAlert: Int = -1
        var pwSaveMode: Int = -1

        /** 是否为有效的参数响应（有 result 或携带版本/名称信息） */
        val isValid: Boolean
            get() = result > 0 || deviceName.isNotEmpty() || verSoft >= 0 || charge >= 0
    }

    /** 全参数查询（真机实测帧 payload = `08 01 18 02`：version=1, field3=2）→ cmd 0x4E23
     *  注意：field3 必须发 2（发 1 设备只回空 result，不带名称/版本/电量） */
    fun encodeParamQuery(): ByteArray {
        val out = ByteArrayOutputStream()
        ProtoIO.writeIntField(out, 1, 1)   // version = 1
        ProtoIO.writeIntField(out, 3, 2)   // fullInquiry = 2（真机实测值）
        return out.toByteArray()
    }

    /**
     * 修改对讲机名称（设备端 transport/f.a(String)）→ cmd 0x4E24
     * deviceName 用 GB2312 编码，bleName 用 UTF-8 编码。
     */
    fun encodeRename(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        ProtoIO.writeIntField(out, 1, 1)   // version = 1
        ProtoIO.writeBytesField(out, 6, name.toByteArray(charset("GB2312")))
        ProtoIO.writeBytesField(out, 7, name.toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    /**
     * 设置功率等级（设备端 Shark transport/g.a(I)：DeviceParam{ch{chTxPower=level}}）→ cmd 0x4E24
     * level: 1=低功率, 2=高功率(5W)
     */
    fun encodeTxPower(level: Int): ByteArray {
        val ch = ByteArrayOutputStream()
        ProtoIO.writeIntField(ch, 3, level)            // ch_txpower = field 3
        val out = ByteArrayOutputStream()
        ProtoIO.writeIntField(out, 1, 1)               // version = 1
        ProtoIO.writeBytesField(out, 12, ch.toByteArray())  // ch = field 12
        return out.toByteArray()
    }

    /**
     * 时间校准（设备端 Shark transport/g.c()：timeStamp = System.currentTimeMillis()/1000）→ cmd 0x4E24
     */
    fun encodeTimeSync(epochSeconds: Long = System.currentTimeMillis() / 1000): ByteArray {
        val out = ByteArrayOutputStream()
        ProtoIO.writeIntField(out, 1, 1)                     // version = 1
        ProtoIO.writeVarint(out, (30 shl 3).toLong())        // timeStamp = field 30, wire 0
        ProtoIO.writeVarint(out, epochSeconds)
        return out.toByteArray()
    }

    /**
     * 双频模式开关（设备端 Shark transport/g.a(Z)）→ cmd 0x4E27
     * ChannelStateOperate{version=1, option=ST_UPDATE, stateMode = 开?2:1}
     * 字段编号：version=1, result=2, option=3, deviceMode=4, stateMode=5, ch1=6, ch2=7, currentChId=8
     * 注意：SHARK_STOPTION 枚举值 ST_UPDATE=0、ST_QUERY=1（实测确定）
     */
    fun encodeDualBand(on: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        ProtoIO.writeIntField(out, 1, 1)              // version = 1
        ProtoIO.writeIntField(out, 3, 0)              // option = SHARK_ST_UPDATE(0)
        ProtoIO.writeIntField(out, 5, if (on) 2 else 1)  // stateMode: 2=双频 1=单频
        return out.toByteArray()
    }

    /**
     * 激活（切换）当前频道（设备端 Shark h0.a(channel,isMajor) → transport/g.a(channel,z,ST_UPDATE)）→ cmd 0x4E27
     * ChannelStateOperate{version=1, option=ST_UPDATE(0), ch1/ch2 = ChannelInfo{chNo, chType}}
     * isMajor=true 写 ch1(field 6)，false 写 ch2(field 7)；ch 只需 chNo(field 1)+chType(field 2)
     * 设备回 0x4E28 回显完整 ch1/ch2（走 handleChannelState 更新）
     */
    fun encodeChannelActivate(ch: ChannelInfo, isMajor: Boolean = true): ByteArray {
        val c = ByteArrayOutputStream()
        ProtoIO.writeIntField(c, 1, ch.no)            // chNo = field 1
        ProtoIO.writeIntField(c, 2, ch.type)          // chType = field 2
        val out = ByteArrayOutputStream()
        ProtoIO.writeIntField(out, 1, 1)              // version = 1
        ProtoIO.writeIntField(out, 3, 0)              // option = SHARK_ST_UPDATE(0)
        ProtoIO.writeBytesField(out, if (isMajor) 6 else 7, c.toByteArray())
        return out.toByteArray()
    }

    /** 信道状态查询（0x4E26：option=ST_QUERY(1)），读回双频状态/当前信道 */
    fun encodeChannelStateQuery(): ByteArray {
        val out = ByteArrayOutputStream()
        ProtoIO.writeIntField(out, 1, 1)              // version = 1
        ProtoIO.writeIntField(out, 3, 1)              // option = ST_QUERY
        return out.toByteArray()
    }

    /** 解析 DeviceParam 响应（含嵌套 ch 的 chTxPower） */
    fun decodeDeviceParam(data: ByteArray): DeviceParam? {
        val p = DeviceParam()
        var seenAny = false
        runCatching {
            ProtoIO.forEachField(data) { field, wire, r ->
                when (field) {
                    1 -> { p.version = r.readVarint().toInt(); seenAny = true }
                    2 -> { p.result = r.readVarint().toInt(); seenAny = true }
                    4 -> p.verHw = r.readVarint().toInt()
                    5 -> p.verSoft = r.readVarint().toInt()
                    6 -> p.deviceName = runCatching {
                        String(r.readBytes(), charset("GB2312")) }.getOrDefault("")
                    7 -> p.bleName = runCatching {
                        String(r.readBytes(), Charsets.UTF_8) }.getOrDefault("")
                    8 -> p.charge = r.readVarint().toInt()
                    12 -> if (wire == 2) {
                        val ch = decodeChannelInfo(r.readBytes())
                        p.txPower = ch.txPower
                    } else r.skipField(wire)
                    13 -> p.voiceAlert = r.readVarint().toInt()
                    15 -> p.pwSaveMode = r.readVarint().toInt()
                    16 -> p.voiceType = r.readVarint().toInt()
                    31 -> p.timeDisplay = r.readVarint().toInt()
                    else -> r.skipField(wire)
                }
            }
        }.onFailure { return null }
        return if (seenAny && p.isValid) p else null
    }

    /** 解析 ChannelStateOperate 响应（双频状态）：返回 stateMode（2=双频 1=单频），无效返回 -1 */
    fun decodeChannelStateMode(data: ByteArray): Int = decodeChannelState(data).stateMode

    /** ChannelStateOperate 解析结果：stateMode(field5) + deviceMode(field4) + 槽位信道 ch1/ch2 */
    class ChannelState(
        val stateMode: Int = -1,    // field 5：2=双频 1=单频
        val deviceMode: Int = -1,   // field 4：SHARK_MODE（NORMAL=0 … SCAN_CH=3 SCAN_FREQ=4 TEAM_M=5 TEAM_S=6）
        val ch1: ChannelInfo? = null,
        val ch2: ChannelInfo? = null
    )

    /**
     * 解析 ChannelStateOperate 响应 = deviceMode + stateMode + 槽位信道（ch1, ch2）。
     * 字段编号（实测确定）：version=1, result=2, option=3,
     * deviceMode=4(varint), stateMode=5, ch1=6, ch2=7, currentChId=8。
     * 真机实测：0x4E28 响应直接携带 field6=ch1、field7=ch2 嵌套 ChannelInfo（含真实频率和信道名）。
     * 注意必须按槽位(field 6/7)区分 ch1/ch2，不能按 chNo 合并——
     * 公众/远距离/自定义信道的 chNo 号段互相重叠（都是 0 基），按 chNo 合并会把旧 ch1 残留顶掉新 ch1。
     */
    fun decodeChannelState(data: ByteArray): ChannelState {
        var stateMode = -1
        var deviceMode = -1
        var ch1: ChannelInfo? = null
        var ch2: ChannelInfo? = null
        runCatching {
            ProtoIO.forEachField(data) { field, wire, r ->
                when (field) {
                    4 -> deviceMode = r.readVarint().toInt()
                    5 -> stateMode = r.readVarint().toInt()
                    6 -> if (wire == 2) {
                        ch1 = runCatching { decodeChannelInfo(r.readBytes()) }.getOrNull()?.takeIf { it.isValid }
                    } else r.skipField(wire)
                    7 -> if (wire == 2) {
                        ch2 = runCatching { decodeChannelInfo(r.readBytes()) }.getOrNull()?.takeIf { it.isValid }
                    } else r.skipField(wire)
                    else -> r.skipField(wire)
                }
            }
        }
        return ChannelState(if (stateMode > 0) stateMode else -1, deviceMode, ch1, ch2)
    }

    /** 兼容旧签名：槽位对拍平为列表（ch1 在前） */
    fun decodeChannelStateChannels(data: ByteArray): List<ChannelInfo> {
        val s = decodeChannelState(data)
        return listOfNotNull(s.ch1, s.ch2)
    }

    // ---------- SEAL/SHARK_ChannelInfoOperate（字段编号 Seal/Shark 一致） ----------
    //
    // 以下两个消息（Seal / Shark）结构完全相同：
    //   version=1, result=2, option=3, ch=4
    // option（SEAL/SHARK_CHOPTION）: INSERT=0 DELETE=1 MODIFY=2 QUERY=3
    // result（SEAL/SHARK_CH_UERR）: CH_OK=0, 查询响应中每帧带一个信道
    // 发送命令码：查询 0x4E29，增删改 0x4E2A（响应均为 0x4E2B）

    /**
     * 信道查询 → cmd 0x4E29。
     * 设备端两种形式（transport/g.a(channel, QUERY)）：
     * - 首次查询：ch 只带 chType（自定义列表传 USERDEF=2，公众列表传 PUBLIC=0）
     *   —— 真机实测：不带 chType 的裸查询设备直接回 EMPTY(result=2)，不返回任何信道
     * - 续页查询：裸 QUERY（version=1, result=CH_OK, option=QUERY），设备回下一信道
     */
    fun encodeChannelInfoQuery(chType: Int = -1): ByteArray {
        val out = ByteArrayOutputStream()
        ProtoIO.writeIntField(out, 1, 1)               // version = 1
        ProtoIO.writeIntField(out, 2, 0)               // result = CH_OK
        ProtoIO.writeIntField(out, 3, ChOption.QUERY)  // option = QUERY
        if (chType >= 0) {
            val ch = ByteArrayOutputStream()
            ProtoIO.writeIntField(ch, 2, chType)       // ch.chType = field 2
            ProtoIO.writeBytesField(out, 4, ch.toByteArray())
        }
        return out.toByteArray()
    }

    /**
     * 信道增/删/改（设备端 f.a(SealSharkChannel, CHOPTION)）→ cmd 0x4E2A
     * - INSERT/MODIFY：ch 携带完整信道信息（no/type/rxfreq/txfreq/name/rxcss/txcss）
     * - DELETE：ch 只带 chNo + chType
     */
    fun encodeChannelOperate(ch: ChannelInfo, option: Int): ByteArray {
        val chBytes = when (option) {
            ChOption.DELETE -> ByteArrayOutputStream().also { c ->
                if (ch.no >= 0) ProtoIO.writeIntField(c, 1, ch.no)
                ProtoIO.writeIntField(c, 2, if (ch.type >= 0) ch.type else ChType.USERDEF)
            }.toByteArray()
            else -> encodeChannelInfo(ChannelInfo().also { full ->
                full.no = ch.no
                full.type = if (ch.type >= 0) ch.type else ChType.USERDEF
                full.rxFreq = ch.rxFreq
                full.txFreq = ch.txFreq
                full.name = ch.name
                full.rxCss = if (ch.rxCss >= 0) ch.rxCss else 0
                full.txCss = if (ch.txCss >= 0) ch.txCss else 0
            })
        }
        val out = ByteArrayOutputStream()
        ProtoIO.writeIntField(out, 1, 1)               // version = 1
        ProtoIO.writeIntField(out, 3, option)          // option
        ProtoIO.writeBytesField(out, 4, chBytes)       // ch = field 4
        return out.toByteArray()
    }

    /** ChannelInfoOperate 响应解析结果 */
    class ChannelOperate {
        var version: Int = 0
        var result: Int = -1          // CH_UERR：0=OK
        var option: Int = -1
        var ch: ChannelInfo? = null
    }

    /** 解析 ChannelInfoOperate 响应（查询响应每帧携带一个信道；增删改响应带 result） */
    fun decodeChannelOperate(data: ByteArray): ChannelOperate? {
        val op = ChannelOperate()
        var seen = false
        runCatching {
            ProtoIO.forEachField(data) { field, wire, r ->
                when (field) {
                    1 -> { op.version = r.readVarint().toInt(); seen = true }
                    2 -> { op.result = r.readVarint().toInt(); seen = true }
                    3 -> { op.option = r.readVarint().toInt(); seen = true }
                    4 -> if (wire == 2) {
                        op.ch = runCatching { decodeChannelInfo(r.readBytes()) }.getOrNull()
                        seen = true
                    } else r.skipField(wire)
                    else -> r.skipField(wire)
                }
            }
        }.onFailure { return null }
        return if (seen) op else null
    }

    /**
     * 解析信道查询响应：设备可能回 SEAL_ChannelInfoOperate（含嵌套 ch）
     * 或直接回 SEAL_ChannelInfo。两种都尝试。
     */
    fun decodeChannelResponse(data: ByteArray): ChannelInfo? {
        // 尝试 1：直接作为 ChannelInfo
        val direct = runCatching { decodeChannelInfo(data) }.getOrNull()
        if (direct != null && direct.isValid) return direct

        // 尝试 2：作为 ChannelInfoOperate，提取嵌套 ch 字段(4)
        var nested: ChannelInfo? = null
        runCatching {
            ProtoIO.forEachField(data) { field, wire, r ->
                if (field == 4 && wire == 2) {
                    nested = runCatching { decodeChannelInfo(r.readBytes()) }.getOrNull()
                } else {
                    r.skipField(wire)
                }
            }
        }
        return nested?.takeIf { it.isValid }
    }
}
