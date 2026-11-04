package com.medicfurry.walkieoffline.protocol

import java.io.ByteArrayOutputStream

/**
 * 手写 protobuf wire format 编解码（varint / length-delimited）。
 * 不引入 protobuf 运行库，保持 APK 轻量。
 */
object ProtoIO {

    // ---------- 编码 ----------

    fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }

    /** 写 varint 字段（wire type 0） */
    fun writeIntField(out: ByteArrayOutputStream, fieldNumber: Int, value: Int) {
        writeVarint(out, (fieldNumber shl 3).toLong())  // key: wire type 0
        writeVarint(out, value.toLong())
    }

    /**
     * 写 uint32 字段（proto2 语义）：按**无符号 32 位**编码，不符号扩展。
     * 与设备端 protobuf-java 的 writeRawVarint32 一致 —— 它对 `uint32` 字段写入的是
     * `value` 的 32 位原码（负值也只编码 5 字节）。
     * 若按有符号 long 编码（10 字节），设备端解析会失败（实测：CRC32 字段符号扩展后
     * 设备对 COMPLE 命令完全不回 ACK，60s 超时）。
     */
    fun writeUInt32Field(out: ByteArrayOutputStream, fieldNumber: Int, value: Int) {
        writeVarint(out, (fieldNumber shl 3).toLong())   // key: wire type 0
        writeVarint(out, value.toLong() and 0xFFFFFFFFL)
    }

    /** 写 length-delimited 字段（wire type 2），bytes 可为子消息或字符串 */
    fun writeBytesField(out: ByteArrayOutputStream, fieldNumber: Int, bytes: ByteArray) {
        writeVarint(out, ((fieldNumber shl 3) or 2).toLong())      // key: wire type 2
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    // ---------- 解码 ----------

    /** 读取结果 */
    class Reader(val data: ByteArray) {
        var pos = 0
            private set

        fun hasMore(): Boolean = pos < data.size

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                if (pos >= data.size) throw IllegalStateException("varint 越界")
                val b = data[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            throw IllegalStateException("varint 过长")
        }

        fun readBytes(): ByteArray {
            val len = readVarint().toInt()
            if (pos + len > data.size) throw IllegalStateException("bytes 越界")
            val result = data.copyOfRange(pos, pos + len)
            pos += len
            return result
        }

        fun skipField(wireType: Int) {
            when (wireType) {
                0 -> readVarint()
                1 -> pos += 8
                2 -> { val len = readVarint().toInt(); pos += len }
                5 -> pos += 4
                else -> throw IllegalStateException("未知 wire type $wireType")
            }
        }
    }

    /** 遍历字段回调：返回 fieldNumber 与 wireType，字段值由调用者按需读取 */
    inline fun forEachField(data: ByteArray, onField: (fieldNumber: Int, wireType: Int, reader: Reader) -> Unit) {
        val r = Reader(data)
        while (r.hasMore()) {
            val key = r.readVarint().toInt()
            val fieldNumber = key ushr 3
            val wireType = key and 0x7
            onField(fieldNumber, wireType, r)
        }
    }
}
