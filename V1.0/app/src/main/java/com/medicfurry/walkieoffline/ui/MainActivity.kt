package com.medicfurry.walkieoffline.ui

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.medicfurry.walkieoffline.MiWalkieApp
import com.medicfurry.walkieoffline.R
import com.medicfurry.walkieoffline.ble.BleConnector
import com.medicfurry.walkieoffline.ble.ConnectorHolder
import com.medicfurry.walkieoffline.databinding.ActivityMainBinding
import com.medicfurry.walkieoffline.debug.DebugLog
import com.medicfurry.walkieoffline.protocol.FreqFormat
import com.medicfurry.walkieoffline.protocol.SealProtos
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 主页面：照设备端 APP（SharkSettingActivity）连接后的设置界面。
 *
 * 功能全部走真实协议（命令码均经真机验证，无占位）：
 * - 频道卡片：握手(0x4E21) → 参数查询(0x4E23) → 状态查询(0x4E26) → 信道查询(0x4E29)
 * - 对讲机名称：改名对话框 → 0x4E24（deviceName GB2312 + bleName UTF-8）
 * - 双频模式：开关 → 0x4E27（stateMode 2/1）
 * - 功率等级：对话框 低(1)/高(2) → 0x4E24（ch.chTxPower）
 * - 时间校准：点击 → 0x4E24（timeStamp = Unix 秒）
 * - 已裁剪：共享位置 / 发送位置间隔
 */
class MainActivity : BaseActivity(), BleConnector.Listener {

    private lateinit var binding: ActivityMainBinding
    private val connector = BleConnector()

    private var connectAttempted = false
    /** 防止双频开关程序化回显时再次触发下发 */
    private var suppressDualBandCallback = false

    // 彩蛋：连续点击「调试终端」窗口 5 次打开隐藏页（间隔超 2 秒重新计数）
    private var terminalTapCount = 0
    private val terminalTapReset = Runnable { terminalTapCount = 0 }
    private val terminalTapHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val device: BluetoothDevice? = getParcelableExtraCompat("device")

        // ---------- 启动路由门：无设备直达主页面时，按流程分发 ----------
        // 流程：首启提示(仅首次) → 权限申请 → 设备扫描 → 选设备回本页(带device)
        if (device == null) {
            val prefs = (application as MiWalkieApp).prefs
            when {
                !prefs.isFirstRunDone ->
                    startActivity(Intent(this, FirstRunActivity::class.java))
                !hasRequiredPermissions() ->
                    startActivity(Intent(this, PermissionActivity::class.java))
                else ->
                    startActivity(Intent(this, DeviceScanActivity::class.java))
            }
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupSettingItems()
        setupEasterEgg()
        setupCopyLogButton()
        observeState()
        connector.listener = this
        ConnectorHolder.connector = connector

        connectAttempted = true
        binding.protocolHint.text = getString(R.string.connecting_state)
        connector.connect(this, device)
    }

    /** 检查蓝牙 + 定位权限是否齐全（缺一即走权限页） */
    private fun hasRequiredPermissions(): Boolean {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun getParcelableExtraCompat(key: String): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(key)
        }

    private fun setupToolbar() {
        binding.toolbar.title = getString(R.string.main_title)
    }

    /** 终端窗口下方的「复制日志」按钮：一键把当前全部日志复制进剪贴板 */
    private fun setupCopyLogButton() {
        binding.copyLogBtn.setOnClickListener {
            val text = DebugLog.lines.value.joinToString("\n")
            if (text.isBlank()) {
                Toast.makeText(this, R.string.copy_log_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("walkie_debug_log", text))
            Toast.makeText(this, R.string.copy_log_done, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 彩蛋：连续点击「调试终端」标题 5 次，打开隐藏形象页。
     * 监听挂标题（terminalHeader）而非终端窗口（terminalScroll）——
     * 终端正文可长按复制，会吞掉点击，挂在窗口上不会触发 onClick。
     */
    private fun setupEasterEgg() {
        binding.terminalHeader.setOnClickListener {
            terminalTapHandler.removeCallbacks(terminalTapReset)
            terminalTapHandler.postDelayed(terminalTapReset, 2000)
            terminalTapCount++
            if (terminalTapCount >= 5) {
                terminalTapCount = 0
                terminalTapHandler.removeCallbacks(terminalTapReset)
                startActivity(Intent(this, EasterEggActivity::class.java))
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            connector.state.collect { state ->
                DebugLog.log("状态 → $state")
                when (state) {
                    BleConnector.State.CONNECTING ->
                        showWaiting(getString(R.string.connecting_state))
                    BleConnector.State.HANDSHAKING ->
                        showWaiting(getString(R.string.handshaking_state))
                    BleConnector.State.READY ->
                        binding.protocolHint.text = getString(R.string.reading_params_state)
                    BleConnector.State.CONNECTED ->
                        showWaiting(getString(R.string.connected_gatt_state))
                    BleConnector.State.DISCONNECTED -> {
                        if (connectAttempted) {
                            DebugLog.log("⚠ " + getString(R.string.connect_failed))
                            connectAttempted = false
                        }
                        showWaiting(getString(R.string.disconnected_state))
                    }
                }
            }
        }
        // 调试终端：实时滚动显示协议日志（节流刷新）。
        // 收集协程只把最新内容存变量，150ms 循环批量刷 TextView——
        // 避免查询链高频日志每行都全量 joinToString+setText 造成卡顿（日志本身一行不少）
        var pendingTerminal: List<String>? = null
        lifecycleScope.launch {
            DebugLog.lines.collect { lines -> pendingTerminal = lines }
        }
        lifecycleScope.launch {
            while (isActive) {
                delay(150)
                val lines = pendingTerminal
                if (lines != null) {
                    pendingTerminal = null
                    binding.terminalText.text = lines.joinToString("\n")
                    binding.terminalScroll.post {
                        binding.terminalScroll.fullScroll(View.FOCUS_DOWN)
                    }
                }
            }
        }
        // 双频状态（StateFlow：页面重建/从子页返回后开关不错位）
        lifecycleScope.launch {
            connector.dualBand.collect { isDual ->
                if (isDual != null) onDualBandState(isDual)
            }
        }
    }

    private fun showWaiting(text: String) {
        binding.protocolHint.visibility = View.VISIBLE
        binding.protocolHint.text = text
    }

    // ---------- BleConnector.Listener（协议事件，主线程回调） ----------

    override fun onReady() {
        Toast.makeText(this, getString(R.string.connect_success), Toast.LENGTH_SHORT).show()
    }

    override fun onChannelsUpdated(channels: List<SealProtos.ChannelInfo>) {
        val first = channels.getOrNull(0) ?: return
        binding.protocolHint.visibility = View.GONE
        binding.ch1Name.text = first.name.ifEmpty { "CH ${first.no}" }
        binding.ch1RxFreq.text = FreqFormat.format(first.rxFreq)
        binding.ch1TxFreq.text = FreqFormat.format(first.txFreq)

        // 副信道：仅在双频模式且设备确实回显 ch2 时显示（设备端同理，单频无副信道卡片）
        val second = channels.getOrNull(1)
        if (second != null && connector.dualBand.value != false) {
            binding.ch2Area.visibility = View.VISIBLE
            binding.ch2Name.text = second.name.ifEmpty { "CH ${second.no}" }
            binding.ch2RxFreq.text = FreqFormat.format(second.rxFreq)
            binding.ch2TxFreq.text = FreqFormat.format(second.txFreq)
        } else {
            binding.ch2Area.visibility = View.GONE
        }
    }

    override fun onDeviceParam(param: SealProtos.DeviceParam) {
        // 硬件版本：设备端存在 设备硬件版本，刷固件时用它过滤目录文件名
        if (param.verHw > 0) {
            (application as MiWalkieApp).prefs.lastVerHw = param.verHw
        }
        // 设备名
        if (param.deviceName.isNotEmpty()) {
            binding.radioNameValue.text = param.deviceName
        }
        // 功率等级回显（1=低 2=高）
        if (param.txPower > 0) {
            binding.powerValue.text = if (param.txPower == 2)
                getString(R.string.power_high_5w) else getString(R.string.power_low)
        }
        // 设置结果反馈
        when (param.result) {
            SealProtos.ParamResult.UPDATE_OK ->
                Toast.makeText(this, getString(R.string.setting_applied), Toast.LENGTH_SHORT).show()
            SealProtos.ParamResult.UPDATE_ERROR ->
                Toast.makeText(this, getString(R.string.setting_failed), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDualBandState(isDual: Boolean) {
        suppressDualBandCallback = true
        binding.dualBandSwitch.isChecked = isDual
        // 单频时没有副信道槽位
        if (!isDual) binding.ch2Area.visibility = View.GONE
        suppressDualBandCallback = false
    }

    override fun onHandshakeFailed(reason: String) {
        DebugLog.log("⚠ " + getString(R.string.connect_failed) + " ($reason)")
    }

    // ---------- 设置项交互（全部真实下发协议命令） ----------

    private fun setupSettingItems() {
        // 频道卡片：照设备端「每个信道卡片各自进对应槽位的频率设置页」
        // （设备端 SharkChannelSettingActivity.a(context, channel, isMajor)：
        //   主信道卡片 → ch1 槽，副信道卡片 → ch2 槽，写入时 setCh1/setCh2 分开发）
        binding.ch1Area.setOnClickListener {
            if (!ensureReady()) return@setOnClickListener
            startActivity(ChannelSettingActivity.intent(this, isMajor = true))
        }
        binding.ch2Area.setOnClickListener {
            if (!ensureReady()) return@setOnClickListener
            startActivity(ChannelSettingActivity.intent(this, isMajor = false))
        }

        // 对讲机名称：改名对话框
        binding.nameSettingItem.setOnClickListener {
            if (!ensureReady()) return@setOnClickListener
            showRenameDialog()
        }

        // 双频模式：开关 → 0x4E27
        binding.dualBandItem.setOnClickListener { binding.dualBandSwitch.toggle() }
        binding.dualBandSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressDualBandCallback) return@setOnCheckedChangeListener
            if (!ensureReady()) {
                suppressDualBandCallback = true
                binding.dualBandSwitch.isChecked = !isChecked
                suppressDualBandCallback = false
                return@setOnCheckedChangeListener
            }
            connector.setDualBand(isChecked)
        }

        // 功率等级：对话框 低/高 → 0x4E24
        binding.powerSettingItem.setOnClickListener {
            if (!ensureReady()) return@setOnClickListener
            showPowerDialog()
        }

        // 时间校准：点击 → 0x4E24（当前 Unix 秒）
        binding.timeSyncItem.setOnClickListener {
            if (!ensureReady()) return@setOnClickListener
            if (connector.syncTime()) {
                Toast.makeText(this, getString(R.string.time_sync_sent), Toast.LENGTH_SHORT).show()
            }
        }

        // 固件升级：按设备端 BaseUpdateActivity，用设备硬件版本过滤
        // /sdcard/Mi-walkie-talkie.firmware/ 下的 shark_*_hw<N>.bin
        binding.firmwareSettingItem.setOnClickListener {
            val verHw = connector.deviceParam.value?.verHw?.takeIf { it > 0 }
                ?: (application as MiWalkieApp).prefs.lastVerHw.takeIf { it > 0 }
                ?: -1
            FirmwareManager.requestFirmwareFlow(this, verHw) { file ->
                startActivity(
                    Intent(this, FirmwareUpdateActivity::class.java)
                        .putExtra(FirmwareUpdateActivity.EXTRA_PATH, file.absolutePath)
                )
            }
        }

        // 关于
        binding.aboutSettingItem.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        // 断开连接
        binding.disconnectItem.setOnClickListener {
            connector.disconnect()
            finish()
        }
    }

    /** 未连接完成协议握手时拦截操作 */
    private fun ensureReady(): Boolean {
        if (connector.state.value != BleConnector.State.READY) {
            Toast.makeText(this, getString(R.string.need_connect_first), Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun showRenameDialog() {
        val input = EditText(this).apply {
            setText(binding.radioNameValue.text)
            hint = getString(R.string.rename_hint)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.setting_radio_name))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    connector.renameDevice(name)
                    binding.radioNameValue.text = name
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPowerDialog() {
        val items = arrayOf(getString(R.string.power_low), getString(R.string.power_high_5w))
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.setting_power))
            .setItems(items) { _, which ->
                // 依据实测：which+1 → 低=1 高=2
                connector.setTxPower(which + 1)
                binding.powerValue.text = items[which]
            }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 300 && resultCode == RESULT_OK) {
            FirmwareManager.handleFilePickResult(this, data?.data)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        connector.disconnect()
        connector.listener = null
        if (ConnectorHolder.connector === connector) ConnectorHolder.connector = null
    }
}
