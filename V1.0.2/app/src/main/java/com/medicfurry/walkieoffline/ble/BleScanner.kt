package com.medicfurry.walkieoffline.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 对讲机 BLE 扫描器。
 *
 * 设备过滤规则（依据实测扫描结果归纳，勿随意放宽）：
 * 广播包 serviceUuids 与对讲机服务 UUID 白名单求交集，非空才是小米对讲机。
 * 白名单 4 个 UUID 分别对应 Seal/Shark/Dolphin/Lite 广播的服务。
 * 无关蓝牙设备（耳机/手环/手机等）一律不显示。
 */
class BleScanner(context: Context) {

    data class ScanDevice(
        val name: String,
        val address: String,
        val rssi: Int,
        val device: BluetoothDevice
    )

    companion object {
        /** 设备名白名单（实测归纳，勿随意放宽） */
        private val WALKIE_SERVICE_UUIDS: Set<ParcelUuid> = setOf(
            ParcelUuid.fromString("0000aee7-0000-1000-8000-00805f9b34fb"),
            ParcelUuid.fromString("0000fdab-0000-1000-8000-00805f9b34fb"),
            ParcelUuid.fromString("0000fee7-0000-1000-8000-00805f9b34fb"),
            ParcelUuid.fromString("0000aec7-0000-1000-8000-00805f9b34fb")
        )

        /** 实现 i/y.b()：广播 serviceUuids ∩ 白名单 ≠ ∅ */
        private fun isWalkieTalkie(result: ScanResult): Boolean {
            val uuids = result.scanRecord?.serviceUuids ?: return false
            return uuids.any { it in WALKIE_SERVICE_UUIDS }
        }
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val scanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner

    private val _devices = MutableStateFlow<Map<String, ScanDevice>>(emptyMap())
    val devices: StateFlow<Map<String, ScanDevice>> = _devices

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // 只保留小米对讲机（设备端过滤规则）
            if (!isWalkieTalkie(result)) return

            val device = result.device
            // 名称优先取广播包里的，其次 GAP 缓存名，兜底统一叫「小米对讲机」
            val name = result.scanRecord?.deviceName
                ?: device.name
                ?: "小米对讲机"
            // 诊断：记录命中的服务 UUID（识别型号：aee7=Seal fdab=Shark fee7=Dolphin aec7=Lite）
            val matched = result.scanRecord?.serviceUuids
                ?.filter { it in WALKIE_SERVICE_UUIDS }
                ?.joinToString(",") { it.uuid.toString().substring(4, 8) }
            Log.i("BleScanner", "命中对讲机: name=$name addr=${device.address} svc=[$matched]")

            _devices.value = _devices.value + (device.address to ScanDevice(
                name = name,
                address = device.address,
                rssi = result.rssi,
                device = device
            ))
        }
    }

    /** 开始扫描，持续 scanDurationMs 毫秒后自动停止 */
    @SuppressLint("MissingPermission")
    fun startScan(scanDurationMs: Long = 10_000L) {
        if (scanner == null || _isScanning.value) return
        _devices.value = emptyMap()
        _isScanning.value = true

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, scanCallback)

        handler.postDelayed({ stopScan() }, scanDurationMs)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (_isScanning.value) {
            scanner?.stopScan(scanCallback)
            _isScanning.value = false
        }
    }
}
