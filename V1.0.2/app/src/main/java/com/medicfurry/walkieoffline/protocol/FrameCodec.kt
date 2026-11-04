package com.medicfurry.walkieoffline.protocol

import java.io.ByteArrayOutputStream

/**
 * BLE 传输帧编解码（依据设备实测通信行为还原，并经真机握手流程逐项验证）：
 *
 * 帧格式（普通命令，SendDataWrap.deviceType != 1）：
 * ```
 * [0]      = 0xFE
 * [1]      = 0x01
 * [2..3]   = totalLen（= 8头 + dataLen + 2CRC），**大端**（e.a([BJII) 高位先写）
 * [4..5]   = **cmd 命令码**（SendDataWrap 第3个构造参数，如 0x4E21），大端
 * [6..7]   = serial（i.b 静态计数 0..0xFFFE 递增），大端
 * [8..]    = protobuf message bytes
 * 末尾+2   = CRC16（覆盖 [0..末尾数据]），大端 [hi, lo]（b.a(S)）
 * ```
 * 注意：[4..5] 不是 transMode！deviceType（4/5）只在 app 侧用于判断是否走
 * 文件传输裸数据分支（==1），不进帧。接收侧按
 * [4..5] 的大端值取 cmd 分发（如 0x4E22=ConnectResp）。
 *
 * 命令码（依据实测调用点常量）：
 * - 0x4E21 = SEAL/SHARK_Connect 连接握手（响应 0x4E22）
 * - 0x4E23 = SEAL/SHARK_DeviceParam 查询
 * - 0x4E24 = SEAL/SHARK_DeviceParam 设置
 * - 0x4E26 = SEAL_ChannelStateOperate 查询
 * - 0x4E27 = ChannelStateOperate 设置（双频开关）
 * - 0x4E29 = SEAL_ChannelInfoOperate（信道查询 → 读回频率）
 * - 0x4E2A = SEAL_ChannelInfoOperate（增删改）
 * - 0x4E30 = SEAL_LocationInfo（位置共享，本 app 裁剪）
 * - 0x4E2C = SHARK_UpGradeFileRequest（升级控制：START/COMPLE/REBOOT）
 * - 0x4E2D = SHARK_UpGradeFilePack（升级数据包，128B/包）
 * - 0x4E2E = SHARK_UpGradeFileResponse（设备响应，含 result + 已确认 offset）
 */
object FrameCodec {

    const val CMD_CONNECT = 0x4E21
    const val CMD_DEVICE_PARAM_GET = 0x4E23
    const val CMD_DEVICE_PARAM_SET = 0x4E24
    const val CMD_CHANNEL_STATE = 0x4E26
    const val CMD_CHANNEL_STATE_SET = 0x4E27   // Shark 双频模式开关（h0.a(Z)）
    const val CMD_CHANNEL_QUERY = 0x4E29
    const val CMD_CHANNEL_MODIFY = 0x4E2A
    const val CMD_LOCATION = 0x4E30

    // 固件升级命令码（实测）：0x4e2c / 0x4e2d / 0x4e2e
    const val CMD_UPGRADE_REQ = 0x4E2C
    const val CMD_UPGRADE_PACK = 0x4E2D
    const val CMD_UPGRADE_RESP = 0x4E2E

    private const val HEAD_0: Byte = 0xFE.toByte()
    private const val HEAD_1: Byte = 0x01

    private var serial = 0

    /** 大端写入 16bit（对应设备端 e.a([BJII)：高位先写） */
    private fun putBE16(frame: ByteArray, off: Int, v: Int) {
        frame[off] = ((v ushr 8) and 0xFF).toByte()
        frame[off + 1] = (v and 0xFF).toByte()
    }

    /** 构造完整发送帧（含头 + protobuf 数据 + CRC16 尾），全部 16bit 字段大端 */
    fun encode(cmd: Int, payload: ByteArray): ByteArray {
        val dataLen = payload.size
        val totalLen = 8 + dataLen + 2

        val frame = ByteArray(totalLen)
        frame[0] = HEAD_0
        frame[1] = HEAD_1
        putBE16(frame, 2, totalLen)
        putBE16(frame, 4, cmd)
        putBE16(frame, 6, serial)
        serial = (serial + 1) and 0xFFFE
        System.arraycopy(payload, 0, frame, 8, dataLen)

        // CRC16 覆盖头+数据，大端追加
        val crc = Crc16.compute(frame.copyOf(8 + dataLen))
        frame[8 + dataLen] = ((crc ushr 8) and 0xFF).toByte()
        frame[9 + dataLen] = (crc and 0xFF).toByte()

        return frame
    }

    /** 解析中的帧回调 */
    class DecodedFrame(val cmd: Int, val payload: ByteArray)

    /**
     * 流式帧解析器：累积 BLE notification 分片，按 FE 01 头 + totalLen 切帧 + CRC 校验。
     */
    class FrameParser {
        private val buffer = ByteArrayOutputStream()
        private var expectLen = -1

        /** 喂入新收到的字节，返回解析出的完整帧列表 */
        fun feed(data: ByteArray): List<DecodedFrame> {
            buffer.write(data)
            val frames = mutableListOf<DecodedFrame>()
            while (true) {
                val buf = buffer.toByteArray()
                // 找同步头 FE 01
                var head = -1
                for (i in 0 until buf.size - 1) {
                    if (buf[i] == HEAD_0 && buf[i + 1] == HEAD_1) { head = i; break }
                }
                if (head < 0) { reset(); return frames }
                if (head > 0) {
                    // 丢弃头前杂散字节
                    buffer.reset()
                    buffer.write(buf, head, buf.size - head)
                    return frames + continueParse(frames)
                }
                if (expectLen < 0) {
                    if (buf.size < 4) return frames
                    // totalLen 大端 @ [2..3]
                    expectLen = ((buf[2].toInt() and 0xFF) shl 8) or
                        (buf[3].toInt() and 0xFF)
                    if (expectLen < 10 || expectLen > 512) { reset(); return frames }
                }
                if (buf.size < expectLen) return frames

                // 取出完整帧
                val frame = buf.copyOfRange(0, expectLen)
                val rest = buf.copyOfRange(expectLen, buf.size)
                buffer.reset()
                if (rest.isNotEmpty()) buffer.write(rest, 0, rest.size)
                expectLen = -1

                // CRC 校验：末尾2字节 = CRC16(前面所有)
                val crcExpected = ((frame[frame.size - 2].toInt() and 0xFF) shl 8) or
                    (frame[frame.size - 1].toInt() and 0xFF)
                val crcActual = Crc16.compute(frame.copyOf(frame.size - 2))
                if (crcExpected != crcActual) {
                    continue  // CRC 失败丢帧，继续找下一帧
                }

                // cmd 在帧头 [4..5]（大端）—— 接收侧按此分发
                val cmd = ((frame[4].toInt() and 0xFF) shl 8) or
                    (frame[5].toInt() and 0xFF)
                val payload = frame.copyOfRange(8, frame.size - 2)
                frames.add(DecodedFrame(cmd = cmd, payload = payload))
            }
        }

        private fun continueParse(frames: MutableList<DecodedFrame>): List<DecodedFrame> {
            // 递归解析剩余缓冲
            val buf = buffer.toByteArray()
            if (buf.size >= 2 && buf[0] == HEAD_0 && buf[1] == HEAD_1) {
                expectLen = -1
                return feed(ByteArray(0))
            }
            return frames
        }

        private fun reset() {
            buffer.reset()
            expectLen = -1
        }
    }
}
