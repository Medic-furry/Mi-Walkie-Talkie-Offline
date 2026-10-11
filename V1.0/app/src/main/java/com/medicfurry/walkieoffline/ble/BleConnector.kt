package com.medicfurry.walkieoffline.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.medicfurry.walkieoffline.debug.DebugLog
import com.medicfurry.walkieoffline.protocol.FrameCodec
import com.medicfurry.walkieoffline.protocol.SealProtos
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque

/**
 * BLE GATT 连接 + Seal/Shark 协议会话。
 *
 * 连接流程（设备端实测要求，已用真机 logcat 逐项验证）：
 * 1. connectGatt → STATE_CONNECTED → requestMtu(163)   ← 是 163，不是 247
 * 2. onMtuChanged → discoverServices
 * 3. onServicesDiscovered → 找 aec7(write)/aec8(notify)/feab(notify)
 * 4. 依次订阅 aec8 → feab（设备端两个都订阅后才握手）
 * 5. feab 的 onDescriptorWrite → 发送 SHARK_Connect 握手帧（帧头 cmd 0x4E21）
 * 6. 握手 ALLOW（响应帧 cmd 0x4E22）→ 依次发：参数全查询(0x4E23) → 信道状态查询(0x4E26) → 信道查询(0x4E29)
 *
 * 响应解析：帧头 [4..5] 携带 cmd（大端），响应 cmd = 请求 cmd + 1
 * （如 0x4E21→0x4E22）；同时保留 FIFO 队列兜底。
 */
class BleConnector {

    enum class State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,          // GATT 已连接（服务发现完成）
        HANDSHAKING,        // 协议握手中
        READY               // 握手完成，可以读写数据
    }

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state

    private val _connectedDevice = MutableStateFlow<BluetoothDevice?>(null)
    val connectedDevice: StateFlow<BluetoothDevice?> = _connectedDevice

    /** 读回的信道数据（双频：index 0/1） */
    private val _channels = MutableStateFlow<List<SealProtos.ChannelInfo>>(emptyList())
    val channels: StateFlow<List<SealProtos.ChannelInfo>> = _channels

    /** 设备上存储的全部自定义信道（0x4E29 查询结果，按信道号排序） */
    private val _customChannels = MutableStateFlow<List<SealProtos.ChannelInfo>>(emptyList())
    val customChannels: StateFlow<List<SealProtos.ChannelInfo>> = _customChannels

    /** 信道操作（增删改）结果：cmd 配对 + 成功/失败，一次性事件由 UI 消费 */
    private val _channelOpResult = MutableStateFlow<SealProtos.ChannelOperate?>(null)
    val channelOpResult: StateFlow<SealProtos.ChannelOperate?> = _channelOpResult

    /** 双频模式状态（null=未知；StateFlow 保证页面重建后开关不错位） */
    private val _dualBand = MutableStateFlow<Boolean?>(null)
    val dualBand: StateFlow<Boolean?> = _dualBand

    /** 设备模式（ChannelStateOperate field 4，SHARK_MODE 枚举：
     *  NORMAL=0 SOS_T=1 SOS_R=2 SCAN_CH=3 SCAN_FREQ=4 TEAM_M=5 TEAM_S=6），断连归零 */
    private val _deviceMode = MutableStateFlow(0)
    val deviceMode: StateFlow<Int> = _deviceMode

    /** 设备参数（0x4E23/0x4E25 查询结果）。verHw 用于刷固件时按 "_hw<N>.bin" 过滤目录，
     *  对应设备硬件版本。 */
    private val _deviceParam = MutableStateFlow<SealProtos.DeviceParam?>(null)
    val deviceParam: StateFlow<SealProtos.DeviceParam?> = _deviceParam

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar2: BluetoothGattCharacteristic? = null
    private var appContext: Context? = null
    private val parser = FrameCodec.FrameParser()
    private val handler = Handler(Looper.getMainLooper())

    /** 设备型号（GATT 服务识别）：4=Shark(fdab) 5=Seal(aee7)，默认 Shark */
    private var deviceType = 4

    /** 待发命令的响应期望队列（FIFO）：每发一帧入队 cmd，收到帧按队首类型解析 */
    private val pendingCmds = ArrayDeque<Int>()

    /** 参数查询完成后是否已发信道状态查询（引导初始化命令序列） */
    private var initStep = 0

    companion object {
        private const val TAG = "BleConnector"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        // 真机实测（真机 logcat）：MTU 请求 163（不是 247！），连接后先 HIGH 优先级，
        // 订阅完成后切回 BALANCED，MTU 完成后延迟 500ms 才 discoverServices。
        // 固件分包逻辑按协商 MTU 走，必须逐项实现，不能自行"优化"。
        private const val REQUEST_MTU = 163
        private const val DISCOVER_DELAY_MS = 500L

        private const val BASE = "-0000-1000-8000-00805f9b34fb"
        // 角色以真机 GATT 表 + 设备端 BLE 中心服务 为准（不要凭名字猜）：
        //   aec7 [WRITE]  = app → 设备（写入通道）
        //   aec8 [NOTIFY] = 设备 → app（订阅 2902）
        //   feab [NOTIFY] = 设备 → app（fdab 服务上的第二通知通道，设备端同样订阅）
        private val UUID_AEC7: java.util.UUID = java.util.UUID.fromString("0000aec7$BASE")  // write
        private val UUID_AEC8: java.util.UUID = java.util.UUID.fromString("0000aec8$BASE")  // notify
        private val UUID_FEAB: java.util.UUID = java.util.UUID.fromString("0000feab$BASE")  // notify2
        private val UUID_FDAB_SVC: java.util.UUID = java.util.UUID.fromString("0000fdab$BASE") // Shark 服务
        private val UUID_AEE7_SVC: java.util.UUID = java.util.UUID.fromString("0000aee7$BASE") // Seal 服务
        private val UUID_CCCD: java.util.UUID = java.util.UUID.fromString("00002902$BASE")  // CCCD
    }

    /** 协议事件回调（主线程） */
    interface Listener {
        fun onReady()
        fun onChannelsUpdated(channels: List<SealProtos.ChannelInfo>)
        fun onDeviceParam(param: SealProtos.DeviceParam)
        fun onDualBandState(isDual: Boolean)
        fun onHandshakeFailed(reason: String)
        /** 自定义信道列表查询完成（0x4E29 查询的流式响应聚合结果） */
        fun onCustomChannels(channels: List<SealProtos.ChannelInfo>) {}
    }

    var listener: Listener? = null

    @SuppressLint("MissingPermission")
    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            logD( "onConnectionStateChange status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _state.value = State.CONNECTED
                    handler.removeCallbacksAndMessages(TIMEOUT_TOKEN)
                    // 依据实测：先 HIGH 连接优先级，再请求 MTU 163
                    logD("requestConnectionPriority(HIGH)")
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    g.requestMtu(REQUEST_MTU)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasReady = _state.value == State.READY
                    _state.value = State.DISCONNECTED
                    _connectedDevice.value = null
                    _channels.value = emptyList()
                    slotCh1 = null; slotCh2 = null
                    _dualBand.value = null
                    clearChannelQueryState()
                    writeChar = null
                    pendingCmds.clear()
                    initStep = 0
                    g.close()
                    if (gatt === g) gatt = null
                    if (wasReady) logI( "连接断开")
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            logD( "onMtuChanged mtu=$mtu status=$status")
            // 依据实测：MTU 完成后延迟 500ms 再发现服务
            handler.postDelayed({
                val gg = gatt ?: return@postDelayed
                logD( "discoverServices (延迟 ${DISCOVER_DELAY_MS}ms 后)")
                gg.discoverServices()
            }, DISCOVER_DELAY_MS)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logW( "服务发现失败 status=$status")
                return
            }
            // 完整打印 GATT 表（诊断用）：service → 特征(属性/描述符)
            for (service in g.services) {
                logI( "SVC ${service.uuid}")
                for (ch in service.characteristics) {
                    val props = buildString {
                        if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) append("NOTIFY ")
                        if (ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) append("INDICATE ")
                        if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) append("WRITE ")
                        if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) append("WRITE_NR ")
                        if (ch.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) append("READ")
                    }
                    val descs = ch.descriptors.joinToString(",") { it.uuid.toString() }
                    logI( "  CHR ${ch.uuid} [$props] desc=[$descs]")
                }
            }
            var notifyChar: BluetoothGattCharacteristic? = null
            notifyChar2 = null
            for (service in g.services) {
                // 按服务识别型号：fdab=Shark(deviceType=4) aee7=Seal(deviceType=5)
                when (service.uuid) {
                    UUID_FDAB_SVC -> deviceType = 4
                    UUID_AEE7_SVC -> deviceType = 5
                }
                for (ch in service.characteristics) {
                    when (ch.uuid) {
                        UUID_AEC7 -> writeChar = ch      // WRITE：app → 设备
                        UUID_AEC8 -> notifyChar = ch     // NOTIFY：设备 → app
                        UUID_FEAB -> notifyChar2 = ch    // NOTIFY：第二通道
                    }
                }
            }
            logD( "write=${writeChar?.uuid} notify=${notifyChar?.uuid} notify2=${notifyChar2?.uuid} deviceType=$deviceType")
            if (notifyChar == null || writeChar == null) {
                listener?.onHandshakeFailed("未找到协议特征值 (aec7写/aec8通知)")
                return
            }
            // 订阅主通知通道 aec8；feab 在 aec8 的 onDescriptorWrite 里接着订阅
            // （设备端 BLE 中心服务：aec8 → feab 两个都订阅完成才发握手）
            subscribeNotify(g, notifyChar, "aec8")
        }

        /** 订阅通知通道（setCharacteristicNotification + 写 2902），失败直接走下一步 */
        @SuppressLint("MissingPermission")
        private fun subscribeNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic, tag: String) {
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(UUID_CCCD)
            if (cccd != null) {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                val ok = g.writeDescriptor(cccd)
                logD( "writeDescriptor($tag) 发起=$ok")
                if (!ok) onDescriptorWrite(g, cccd, BluetoothGatt.GATT_SUCCESS)
            } else {
                logW( "$tag 上未找到 2902 描述符！")
                onSubscribeStepDone(ch)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            logD( "onDescriptorWrite chr=${d.characteristic.uuid} status=$status")
            onSubscribeStepDone(d.characteristic)
        }

        /** 订阅链：aec8 完成 → 订阅 feab；feab 完成 → 切 BALANCED 优先级 → 发握手 */
        @SuppressLint("MissingPermission")
        private fun onSubscribeStepDone(ch: BluetoothGattCharacteristic) {
            when (ch.uuid) {
                UUID_AEC8 -> {
                    val n2 = notifyChar2
                    val g = gatt
                    if (n2 != null && g != null) {
                        subscribeNotify(g, n2, "feab")
                    } else {
                        sendHandshake()
                    }
                }
                else -> {
                    // 依据实测：feab 订阅完成后 requestConnectionPriority(BALANCED) 再握手
                    gatt?.let {
                        logD("requestConnectionPriority(BALANCED)")
                        it.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                    }
                    sendHandshake()
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            logD( "onCharacteristicWrite status=$status")
        }

        /** API 33+ 的新回调签名 */
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            logI( "onChanged ${c.uuid} ${value.size}B: ${value.toHex()}")
            val frames = parser.feed(value)
            if (frames.isEmpty()) {
                logW( "未解析出完整帧（可能分片/帧头不符/CRC失败）")
                return
            }
            handleFrames(frames)
        }

        /**
         * API 32 及以下的旧回调签名（2 参）。测试机 MI 9 是 Android 11 (API 30)，
         * 框架只会调这个版本！只重写 3 参版本在老机器上永远收不到通知——
         * 之前所有"设备无响应"实际是响应到了但回调没接上。
         */
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = c.value ?: return
            onCharacteristicChanged(g, c, value)
        }
    }

    private val TIMEOUT_TOKEN = object {}

    private fun ByteArray.toHex(): String =
        joinToString(" ") { "%02X".format(it) }

    // 日志同时进 logcat 和 App 内调试终端
    private fun logD(msg: String) = DebugLog.d(TAG, msg)
    private fun logI(msg: String) = DebugLog.i(TAG, msg)
    private fun logW(msg: String) = DebugLog.w(TAG, msg)

    // ---------- 帧处理 ----------

    private fun handleFrames(frames: List<FrameCodec.DecodedFrame>) {
        handler.post {
            for (frame in frames) {
                val payload = frame.payload
                // 实测响应命令码：握手 0x4E21→0x4E22(+1)；
                // 参数 0x4E23/0x4E24→0x4E25；状态 0x4E26/0x4E27→0x4E28；信道 0x4E29/0x4E2A→0x4E2B(+2)
                val queued = pendingCmds.poll() ?: 0
                val expected = when (frame.cmd) {
                    0x4E22 -> FrameCodec.CMD_CONNECT
                    0x4E25 -> FrameCodec.CMD_DEVICE_PARAM_GET
                    0x4E28 -> FrameCodec.CMD_CHANNEL_STATE
                    // 信道查询(0x4E29)与增删改(0x4E2A)的响应都是 0x4E2B：
                    // 队列里最近一条信道命令决定语义（查询=流式列表，增删改=result 确认）
                    0x4E2B -> if (queued == FrameCodec.CMD_CHANNEL_MODIFY)
                        FrameCodec.CMD_CHANNEL_MODIFY else FrameCodec.CMD_CHANNEL_QUERY
                    // 升级控制(0x4E2C)与数据包(0x4E2D)的响应都是 0x4E2E，由队列里的命令区分
                    0x4E2E -> if (queued == FrameCodec.CMD_UPGRADE_PACK)
                        FrameCodec.CMD_UPGRADE_PACK else FrameCodec.CMD_UPGRADE_REQ
                    else -> queued
                }
                logD( "收到帧 cmd=0x${Integer.toHexString(frame.cmd)} ${payload.size}B, 期待cmd=0x${Integer.toHexString(expected)}")
                when (expected) {
                    FrameCodec.CMD_CONNECT -> if (!handleConnect(payload)) fallbackParse(payload)
                    FrameCodec.CMD_DEVICE_PARAM_GET,
                    FrameCodec.CMD_DEVICE_PARAM_SET -> if (!handleDeviceParam(payload)) fallbackParse(payload)
                    FrameCodec.CMD_CHANNEL_QUERY,
                    FrameCodec.CMD_CHANNEL_MODIFY -> if (!handleChannel(payload)) fallbackParse(payload)
                    FrameCodec.CMD_CHANNEL_STATE,
                    FrameCodec.CMD_CHANNEL_STATE_SET -> if (!handleChannelState(payload)) fallbackParse(payload)
                    FrameCodec.CMD_UPGRADE_REQ,
                    FrameCodec.CMD_UPGRADE_PACK -> handleUpgradeResponse(payload)
                    else -> fallbackParse(payload)
                }
            }
        }
    }

    /** 兜底：按消息特征盲猜（设备主动推送场景） */
    private fun fallbackParse(payload: ByteArray) {
        if (handleConnect(payload)) return
        if (handleDeviceParam(payload)) return
        if (handleChannel(payload)) return
        handleChannelState(payload)
    }

    private fun handleConnect(payload: ByteArray): Boolean {
        val connect = runCatching { SealProtos.decodeConnect(payload) }.getOrNull() ?: return false
        if (connect.connCode == 0) return false
        when (connect.connCode) {
            SealProtos.ConnCode.ALLOW -> {
                logI( "握手通过 deviceId=${connect.deviceId}，开始初始化命令序列")
                handler.removeCallbacksAndMessages(TIMEOUT_TOKEN)   // 停掉握手重发定时器
                // 设备端 握手响应处理：ALLOW 后把设备分配的 deviceId 持久化
                // （对应设备分配的 deviceId），下次握手带上
                if (connect.deviceId > 0) saveDeviceId(connect.deviceId)
                _state.value = State.READY
                listener?.onReady()
                // 时序（实测要求）：先查全参数
                initStep = 1
                sendParamQuery()
            }
            SealProtos.ConnCode.WAIT ->
                logI( "设备要求在对讲机屏幕上确认连接")
            else -> listener?.onHandshakeFailed("握手被拒绝 connCode=${connect.connCode}")
        }
        return true
    }

    private fun handleDeviceParam(payload: ByteArray): Boolean {
        val param = runCatching { SealProtos.decodeDeviceParam(payload) }.getOrNull() ?: return false
        logI( "DeviceParam: result=${param.result} name=${param.deviceName} " +
            "verSoft=${param.verSoft} verHw=${param.verHw} power=${param.txPower} charge=${param.charge}")
        _deviceParam.value = param
        listener?.onDeviceParam(param)
        // 初始化序列：参数查询响应后 → 信道状态查询 → 信道查询
        if (initStep == 1 && param.result == SealProtos.ParamResult.QUERY_OK) {
            initStep = 2
            sendChannelStateQuery()
        }
        return true
    }

    private fun handleChannel(payload: ByteArray): Boolean {
        val op = runCatching { SealProtos.decodeChannelOperate(payload) }.getOrNull()
        if (op != null && (op.result >= 0 || op.ch != null)) {
            val ch = op.ch
            if (op.option == SealProtos.ChOption.QUERY || op.option < 0) {
                // 查询链（设备端 频道列表逻辑 o()）：每帧 result=QUERY_CONTINUE 的信道
                // 只进临时缓冲 queryBuffer（按 no 去重），立即发裸 QUERY 续页，UI 不逐帧刷新；
                // 收到 EMPTY/FINISH（或兜底超时）才由 channelFlushRunnable 一次性替换显示列表
                if (ch != null && ch.isValid) {
                    logI( "信道: no=${ch.no} rx=${ch.rxFreq} tx=${ch.txFreq} name=${ch.name} " +
                        "rxCss=${ch.rxCss} txCss=${ch.txCss} type=${ch.type} result=${op.result}")
                    val idx = queryBuffer.indexOfFirst { it.no == ch.no }
                    if (idx >= 0) queryBuffer[idx] = ch else queryBuffer.add(ch)
                    if (channelQueryActive) {
                        logD( "续页查询下一信道")
                        send(FrameCodec.CMD_CHANNEL_QUERY, SealProtos.encodeChannelInfoQuery())
                    }
                } else if (op.result >= 0) {
                    logI( "信道查询结束: result=${op.result}")
                    if (channelQueryActive &&
                        (op.result == SealProtos.ChResult.EMPTY || op.result == SealProtos.ChResult.QUERY_FINISH)
                    ) {
                        handler.removeCallbacks(channelFlushRunnable)
                        channelFlushRunnable.run()
                    }
                }
            } else {
                // 增删改确认（真机实测：响应带 result=CH_OK 且回显完整信道）
                logI( "信道操作响应: result=${op.result} option=${op.option} " +
                    (ch?.let { "no=${it.no} name=${it.name}" } ?: ""))
                if (op.result == SealProtos.ChResult.OK) {
                    when (op.option) {
                        SealProtos.ChOption.DELETE -> removeCustomChannel(ch?.no ?: -1)
                        else -> ch?.takeIf { it.isValid }?.let { addCustomChannel(it) }
                    }
                }
                _channelOpResult.value = op
            }
            return true
        }
        // 兜底：直接作为 ChannelInfo
        val channel = runCatching { SealProtos.decodeChannelResponse(payload) }.getOrNull() ?: return false
        logI( "收到信道(裸): no=${channel.no} rx=${channel.rxFreq} tx=${channel.txFreq} name=${channel.name}")
        addCustomChannel(channel)
        return true
    }

    // 信道查询是续页链式响应（每个自定义信道一帧，设备回 EMPTY/FINISH 结束），
    // 静默窗口只作兜底（设备丢帧时收尾）。
    // 设备端 频道列表逻辑：查询链帧先进临时列表 r，收到 EMPTY 时
    // k.clear()+k.addAll(r)+notifyDataSetChanged 一次性替换，UI 不逐帧刷新
    private var channelQueryActive = false

    /** 查询链临时缓冲（对应设备端临时列表 r），链结束才一次性倒入 _customChannels */
    private val queryBuffer = mutableListOf<SealProtos.ChannelInfo>()

    private val channelFlushRunnable = Runnable {
        channelQueryActive = false
        // 一次性替换显示列表（设备端 f(op)：k.clear()+k.addAll(r)）
        val list = queryBuffer.sortedBy { it.no }
        queryBuffer.clear()
        _customChannels.value = list
        logI( "信道查询完成，共 ${list.size} 个")
        listener?.onCustomChannels(list)
    }

    /** 断连/重连时清理信道查询缓存（参照 slotCh1/slotCh2 清理点） */
    private fun clearChannelQueryState() {
        _customChannels.value = emptyList()
        queryBuffer.clear()
        channelQueryActive = false
        handler.removeCallbacks(channelFlushRunnable)
        _deviceMode.value = 0
        _deviceParam.value = null
        // 升级途中掉线：设备端 升级辅助逻辑$d 在 !node.j.b().a() 时立即 b(false)
        if (otaState != OTA_IDLE) finishUpgrade(false, 0, "连接已断开")
    }

    private fun addCustomChannel(ch: SealProtos.ChannelInfo) {
        val current = _customChannels.value.toMutableList()
        val idx = current.indexOfFirst { it.no == ch.no }
        if (idx >= 0) current[idx] = ch else current.add(ch)
        current.sortBy { it.no }
        _customChannels.value = current
        // 查询链仍在进行时同步补丁缓冲：否则 EMPTY 收尾会用旧缓冲覆盖刚写入的信道
        if (channelQueryActive) {
            val bi = queryBuffer.indexOfFirst { it.no == ch.no }
            if (bi >= 0) queryBuffer[bi] = ch else queryBuffer.add(ch)
        }
    }

    private fun removeCustomChannel(no: Int) {
        val current = _customChannels.value.toMutableList()
        if (current.removeAll { it.no == no }) {
            _customChannels.value = current
        }
        if (channelQueryActive) queryBuffer.removeAll { it.no == no }
    }

    /** 信道操作（增删改）结果事件由 UI 消费 */
    fun consumeChannelOpResult() { _channelOpResult.value = null }

    private fun handleChannelState(payload: ByteArray): Boolean {
        val cs = SealProtos.decodeChannelState(payload)
        val stateMode = cs.stateMode
        val ch1 = cs.ch1
        val ch2 = cs.ch2
        if (stateMode <= 0 && cs.deviceMode < 0 && ch1 == null && ch2 == null) return false
        // deviceMode（field 4，SHARK_MODE：0=NORMAL 3/4=扫描 5/6=组队），
        // UI 激活频道前据此拦截（设备端 toast scan/team_mode_can_not_setting）
        if (cs.deviceMode >= 0) {
            logI( "设备模式: deviceMode=${cs.deviceMode}")
            _deviceMode.value = cs.deviceMode
        }
        if (stateMode > 0) {
            logI( "信道状态: stateMode=$stateMode (${if (stateMode == 2) "双频" else "单频"})")
            _dualBand.value = (stateMode == 2)
            listener?.onDualBandState(stateMode == 2)
        }
        // 0x4E28 响应按槽位携带 ch1(field6)/ch2(field7)：直接整槽替换。
        // 不能按 chNo 合并——公众/远距离/自定义 chNo 号段重叠，旧 ch1 会残留顶掉新 ch1（真机实测）
        var changed = false
        if (ch1 != null) {
            logI( "ch1: no=${ch1.no} type=${ch1.type} rx=${ch1.rxFreq} tx=${ch1.txFreq} name=${ch1.name}")
            slotCh1 = ch1; changed = true
        }
        if (ch2 != null) {
            logI( "ch2: no=${ch2.no} type=${ch2.type} rx=${ch2.rxFreq} tx=${ch2.txFreq} name=${ch2.name}")
            slotCh2 = ch2; changed = true
        }
        if (changed) emitChannelSlots()
        // 初始化序列：状态查询后 → 信道查询
        if (initStep == 2) {
            initStep = 3
            sendChannelQuery()
        }
        return true
    }

    /** 当前 ch1/ch2 槽位（0x4E28 响应整槽覆盖；未下发的槽保持原值） */
    private var slotCh1: SealProtos.ChannelInfo? = null
    private var slotCh2: SealProtos.ChannelInfo? = null

    private fun emitChannelSlots() {
        val list = listOfNotNull(slotCh1, slotCh2)
        _channels.value = list
        listener?.onChannelsUpdated(list)
    }

    // ---------- 固件升级状态机 ----------
    // 固件升级流程（依据实测行为还原，勿改）：
    //   START(0x4E2C, fCmd=1, 超时10s) → ACK
    //   → 循环发数据包(0x4E2D, 128B, offset+xor) 「停-等」，每包 ACK 推进 offset（超时10s 重发，连续5次放弃）
    //   → 全部发完 → COMPLE(0x4E2C, fCmd=2, fSize+整文件CRC32, 超时60s)
    //   → ACK OK → REBOOT(0x4E2C, fCmd=3) → onSuccess
    //   设备回 0x4E2E：result(1=OK 2=ERROR 3=LOW_POWER) + offset(已确认字节)
    //   进度 = offset / 文件总字节 * 100（设备端实现）

    interface FirmwareUpgradeCallback {
        /** percent 0..100（设备端进度回调到不了 100，这里 100 由 onSuccess 补发） */
        fun onProgress(percent: Int, sentBytes: Int, totalBytes: Int)
        fun onSuccess()
        /** code: 0=一般失败，1=低电量失败（设备端 升级辅助逻辑.a() → d(false, 1)） */
        fun onFailure(code: Int, message: String)
    }

    /** 固定 128 字节/包（设备端实现，与 MTU 无关） */
    private val otaPackSize = 128
    private val OTA_IDLE = -1
    private val OTA_READY = 0
    private val OTA_TRANSMITTING = 1
    private val OTA_COMPLETED = 2

    private var otaData: ByteArray? = null
    private var otaCrc32: Int = 0
    private var otaOffset: Int = 0
    private var otaState: Int = OTA_IDLE
    private var otaFailCount: Int = 0
    private var otaRespCount: Int = 0
    private var otaCallback: FirmwareUpgradeCallback? = null
    private val otaTimeoutRunnable = Runnable { onOtaTimeout() }

    val isUpgrading: Boolean get() = otaState != OTA_IDLE

    /** 开始固件升级（设备端入口：j.a().a(context, version=1, path, cb)） */
    fun startFirmwareUpgrade(data: ByteArray, cb: FirmwareUpgradeCallback) {
        if (_state.value != State.READY) {
            cb.onFailure(0, "设备未连接或未完成握手")
            return
        }
        if (data.isEmpty()) {
            cb.onFailure(0, "固件文件为空")
            return
        }
        val crc = java.util.zip.CRC32()
        crc.update(data, 0, data.size)
        otaData = data
        otaCrc32 = (crc.value and 0xFFFFFFFFL).toInt()   // 设备端实现 强转 int
        otaOffset = 0
        otaState = OTA_READY
        otaFailCount = 0
        otaCallback = cb
        logI("固件升级开始：${data.size} 字节 / ${(data.size + otaPackSize - 1) / otaPackSize} 包，crc32=0x${Integer.toHexString(otaCrc32)}")
        if (!sendUpgradeRequest(SealProtos.UpgradeCmd.START, 10_000)) {
            finishUpgrade(false, 0, "升级请求发送失败")
        }
    }

    private fun sendUpgradeRequest(fCmd: Int, timeoutMs: Int): Boolean {
        val payload = SealProtos.encodeUpgradeRequest(fCmd, otaData?.size ?: 0, otaCrc32)
        val ok = send(FrameCodec.CMD_UPGRADE_REQ, payload)
        if (ok && timeoutMs > 0) armOtaTimeout(timeoutMs)
        return ok
    }

    private fun sendPack(offset: Int) {
        val data = otaData ?: return
        if (offset >= data.size) return
        val n = minOf(otaPackSize, data.size - offset)
        var xor = 0
        for (i in 0 until n) xor = xor xor (data[offset + i].toInt() and 0xFF)
        val chunk = data.copyOfRange(offset, offset + n)
        if (!send(FrameCodec.CMD_UPGRADE_PACK, SealProtos.encodeUpgradePack(offset, chunk, xor))) {
            finishUpgrade(false, 0, "数据包发送失败")
            return
        }
        armOtaTimeout(10_000)
    }

    private fun handleUpgradeResponse(payload: ByteArray) {
        val resp = runCatching { SealProtos.decodeUpgradeResponse(payload) }.getOrNull() ?: return
        cancelOtaTimeout()
        if (otaState == OTA_IDLE) return
        val total = otaData?.size ?: return
        // 传输中每包都会回 ACK，按 32 包节流一次，避免刷爆 logcat
        if (otaState != OTA_TRANSMITTING || (++otaRespCount and 31) == 0) {
            logD("升级响应 result=${resp.result} offset=${resp.offset} state=$otaState")
        }
        if (resp.result == SealProtos.UpgradeResult.LOW_POWER) {
            finishUpgrade(false, 1, "对讲机电量不足")
            return
        }
        val ok = resp.result == SealProtos.UpgradeResult.OK
        when (otaState) {
            OTA_READY -> {
                if (ok && resp.offset >= 0 && resp.offset < total) {
                    otaOffset = resp.offset
                    otaState = OTA_TRANSMITTING
                    otaFailCount = 0
                    logI("升级握手通过，起始 offset=$otaOffset")
                    sendPack(otaOffset)
                } else {
                    finishUpgrade(false, 0, "设备拒绝升级（result=${resp.result}, offset=${resp.offset}）")
                }
            }
            OTA_TRANSMITTING -> {
                if (ok) {
                    otaFailCount = 0
                    otaOffset = resp.offset
                    if (otaOffset < total) {
                        emitProgress()
                        sendPack(otaOffset)
                    } else {
                        otaState = OTA_COMPLETED
                        emitProgress()
                        logI("数据发送完毕，请求 CRC32 校验 size=$total crc32=0x${Integer.toHexString(otaCrc32)}")
                        if (!sendUpgradeRequest(SealProtos.UpgradeCmd.COMPLE, 60_000)) {
                            finishUpgrade(false, 0, "校验命令发送失败")
                        }
                    }
                } else {
                    otaFailCount++
                    if (otaFailCount >= 5) finishUpgrade(false, 0, "连续失败 5 次")
                    else {
                        logW("第 $otaFailCount 次失败，重发 offset=$otaOffset")
                        sendPack(otaOffset)
                    }
                }
            }
            OTA_COMPLETED -> {
                if (ok) {
                    logI("CRC32 校验通过，发送重启命令")
                    if (sendUpgradeRequest(SealProtos.UpgradeCmd.REBOOT, 0)) finishUpgrade(true, 0, "")
                    else finishUpgrade(false, 0, "重启命令发送失败")
                } else {
                    finishUpgrade(false, 0, "CRC32 校验未通过")
                }
            }
        }
    }

    private fun emitProgress() {
        val total = otaData?.size ?: return
        if (total <= 0) return
        val percent = ((otaOffset.toFloat() / total.toFloat()) * 100f).toInt().coerceIn(0, 100)
        otaCallback?.onProgress(percent, otaOffset, total)
    }

    private fun armOtaTimeout(ms: Int) {
        handler.removeCallbacks(otaTimeoutRunnable)
        handler.postDelayed(otaTimeoutRunnable, ms.toLong())
    }

    private fun cancelOtaTimeout() = handler.removeCallbacks(otaTimeoutRunnable)

    /** 设备端实现：连续失败 5 次放弃；ready/completed 超时直接放弃；传输中重发 */
    private fun onOtaTimeout() {
        otaFailCount++
        if (otaFailCount >= 5) {
            finishUpgrade(false, 0, "连续超时 5 次")
            return
        }
        when (otaState) {
            OTA_READY -> finishUpgrade(false, 0, "升级请求超时")
            OTA_COMPLETED -> finishUpgrade(false, 0, "等待 CRC32 结果超时")
            else -> {
                logW("第 $otaFailCount 次超时，重发 offset=$otaOffset")
                sendPack(otaOffset)
            }
        }
    }

    /** 统一出口（设备端 升级辅助逻辑.d(boolean, int)）：收尾只回调一次 */
    private fun finishUpgrade(success: Boolean, code: Int, message: String) {
        cancelOtaTimeout()
        val cb = otaCallback
        otaData = null
        otaState = OTA_IDLE
        otaOffset = 0
        otaFailCount = 0
        otaCallback = null
        cb ?: return
        if (success) {
            logI("固件升级完成")
            cb.onSuccess()
        } else {
            logW("固件升级失败 code=$code $message")
            cb.onFailure(code, message)
        }
    }

    // ---------- 发送（全部命令码（均经真机验证）） ----------

    @SuppressLint("MissingPermission")
    private fun send(cmd: Int, payload: ByteArray): Boolean {
        val g = gatt ?: return false
        val wc = writeChar ?: return false
        val frame = FrameCodec.encode(cmd, payload)
        wc.value = frame
        val ok = g.writeCharacteristic(wc)
        if (ok) {
            pendingCmds.add(cmd)
            // 升级数据包每秒上百帧，全量 hex 会把 logcat 撑爆导致丢日志（真机实测丢包），
            // 这里只打 offset/长度，不打印 payload
            if (cmd == FrameCodec.CMD_UPGRADE_PACK) {
                logD("发送数据包 ${frame.size}B")
            } else {
                logI("发送 cmd=0x${Integer.toHexString(cmd)} ${frame.size}B: ${frame.toHex()}")
            }
        }
        return ok
    }

    /** 握手（cmd 0x4E21，Seal/Shark 同命令码；deviceId 回显设备下发值）。
     *  设备偶尔丢帧（真机实测），设备端 连接线程 也有重发机制：这里 5s 未收到响应自动重发，最多 3 次。 */
    @SuppressLint("MissingPermission")
    private fun sendHandshake() {
        _state.value = State.HANDSHAKING
        handshakeRetries = 0
        doSendHandshake()
    }

    private var handshakeRetries = 0

    private fun doSendHandshake() {
        val storedId = loadDeviceId()
        send(FrameCodec.CMD_CONNECT,
            SealProtos.encodeConnect(SealProtos.Connect(deviceType = deviceType, deviceId = storedId)))
        handler.postDelayed({
            if (_state.value == State.HANDSHAKING) {
                if (handshakeRetries < 3) {
                    handshakeRetries++
                    logW( "握手无响应，第 $handshakeRetries 次重发")
                    doSendHandshake()
                } else {
                    logW( "握手重发 3 次仍无响应，放弃")
                    listener?.onHandshakeFailed("握手超时（设备无响应）")
                    disconnect()
                }
            }
        }, TIMEOUT_TOKEN, 5_000L)
    }

    // ---------- deviceId 持久化（对应设备端 本地持久化读写，设备分配的 deviceId） ----------

    private fun prefs() = appContext?.getSharedPreferences("sp_walkie", Context.MODE_PRIVATE)

    private fun saveDeviceId(id: Int) {
        prefs()?.edit()?.putInt("device_id", id)?.apply()
    }

    /**
     * 设备端行为：Connect 请求必须回显设备自己的 deviceId（设备在 ConnectResp 的 field3 下发，
     * 设备端存入 shark_device_id 后每次握手带上）；不带/带错时设备静默丢帧（真机实测：
     * 不带 deviceId 发送后设备无任何响应，约 20s 后链路被设备侧断开 status=133）。
     * 默认值 12401640 来自真机 logcat 抓到的本机 ConnectResp（varint E8 F7 F4 05），
     * 与设备端 App 在该设备上实际发送的请求完全一致；首次 ALLOW 后会被设备下发值覆盖。
     */
    private fun loadDeviceId(): Int = prefs()?.getInt("device_id", 12_401_640) ?: 12_401_640

    /** 全参数查询（cmd 0x4E23）：读设备名/版本/电量/功率 */
    fun sendParamQuery() = send(FrameCodec.CMD_DEVICE_PARAM_GET, SealProtos.encodeParamQuery())

    /** 信道查询（cmd 0x4E29）：读回设备上全部自定义信道。
     *  设备端 频道列表逻辑：首发 QUERY+ch{chType=USERDEF}，之后每帧续页裸 QUERY，
     *  设备回 EMPTY/FINISH 结束（真机实测裸查询首发只回 EMPTY） */
    fun sendChannelQuery(): Boolean {
        // 不清空 _customChannels：保留旧数据当缓存即时显示（与设备端 greendao DB 缓存同效），
        // 查询链结束才由 channelFlushRunnable 一次性替换（设备端 频道列表逻辑 o()/f(op)）
        queryBuffer.clear()
        channelQueryActive = true
        handler.removeCallbacks(channelFlushRunnable)
        handler.postDelayed(channelFlushRunnable, 10_000)  // 设备端 0x2710 兜底超时
        return send(FrameCodec.CMD_CHANNEL_QUERY,
            SealProtos.encodeChannelInfoQuery(SealProtos.ChType.USERDEF))
    }

    /** 修改信道（cmd 0x4E2A，option=MODIFY）：频率/名称/亚音 */
    fun modifyChannel(ch: SealProtos.ChannelInfo) =
        send(FrameCodec.CMD_CHANNEL_MODIFY, SealProtos.encodeChannelOperate(ch, SealProtos.ChOption.MODIFY))

    /** 新增信道（cmd 0x4E2A，option=INSERT） */
    fun insertChannel(ch: SealProtos.ChannelInfo) =
        send(FrameCodec.CMD_CHANNEL_MODIFY, SealProtos.encodeChannelOperate(ch, SealProtos.ChOption.INSERT))

    /** 删除信道（cmd 0x4E2A，option=DELETE，只带 chNo+chType） */
    fun deleteChannel(ch: SealProtos.ChannelInfo) =
        send(FrameCodec.CMD_CHANNEL_MODIFY, SealProtos.encodeChannelOperate(ch, SealProtos.ChOption.DELETE))

    /** 信道状态查询（cmd 0x4E26）：读双频模式状态 */
    fun sendChannelStateQuery() = send(FrameCodec.CMD_CHANNEL_STATE, SealProtos.encodeChannelStateQuery())

    /** 修改对讲机名称（cmd 0x4E24） */
    fun renameDevice(name: String) = send(FrameCodec.CMD_DEVICE_PARAM_SET, SealProtos.encodeRename(name))

    /** 设置功率等级（cmd 0x4E24）：1=低 2=高(5W) */
    fun setTxPower(level: Int) = send(FrameCodec.CMD_DEVICE_PARAM_SET, SealProtos.encodeTxPower(level))

    /** 时间校准（cmd 0x4E24）：写入当前 Unix 秒时间戳 */
    fun syncTime() = send(FrameCodec.CMD_DEVICE_PARAM_SET, SealProtos.encodeTimeSync())

    /** 双频模式开关（cmd 0x4E27） */
    fun setDualBand(on: Boolean) = send(FrameCodec.CMD_CHANNEL_STATE_SET, SealProtos.encodeDualBand(on))

    /** 激活（切换）当前频道（cmd 0x4E27，设备端 h0.a(channel,isMajor)）；
     *  设备回 0x4E28 回显完整 ch1/ch2，由 handleChannelState 更新卡片与高亮 */
    fun sendChannelActivate(ch: SealProtos.ChannelInfo, isMajor: Boolean = true): Boolean {
        logI( "切换频道: no=${ch.no} type=${ch.type} name=${ch.name} -> ${if (isMajor) "ch1" else "ch2"}")
        return send(FrameCodec.CMD_CHANNEL_STATE_SET, SealProtos.encodeChannelActivate(ch, isMajor))
    }

    // ---------- 连接管理 ----------

    @SuppressLint("MissingPermission")
    fun connect(context: Context, device: BluetoothDevice) {
        disconnect()
        appContext = context.applicationContext
        _state.value = State.CONNECTING
        _connectedDevice.value = device
        _channels.value = emptyList()
        slotCh1 = null; slotCh2 = null
        _dualBand.value = null
        clearChannelQueryState()
        initStep = 0

        gatt = device.connectGatt(context, false, callback)

        handler.postDelayed({
            if (_state.value == State.CONNECTING || _state.value == State.HANDSHAKING) {
                logW( "连接超时，放弃")
                listener?.onHandshakeFailed("连接超时")
                disconnect()
            }
        }, TIMEOUT_TOKEN, CONNECT_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        handler.removeCallbacksAndMessages(TIMEOUT_TOKEN)
        gatt?.let { g ->
            g.disconnect()
            g.close()
        }
        gatt = null
        writeChar = null
        pendingCmds.clear()
        initStep = 0
        _state.value = State.DISCONNECTED
        _connectedDevice.value = null
        _channels.value = emptyList()
        slotCh1 = null; slotCh2 = null
        _dualBand.value = null
        clearChannelQueryState()
    }
}
