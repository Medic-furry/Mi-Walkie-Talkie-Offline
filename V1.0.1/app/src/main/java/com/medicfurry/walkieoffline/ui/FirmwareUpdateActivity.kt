package com.medicfurry.walkieoffline.ui

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.medicfurry.walkieoffline.R
import com.medicfurry.walkieoffline.ble.BleConnector
import com.medicfurry.walkieoffline.ble.ConnectorHolder
import com.medicfurry.walkieoffline.databinding.ActivityFirmwareUpdateBinding
import com.medicfurry.walkieoffline.debug.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 固件升级页 —— 状态机按设备端升级流程：
 *   x=1 传输中 → x=5 成功 → x=6 失败 → x=7 低电量失败；
 *   升级中按返回键只弹「正在升级固件，请稍候…」提示，不允许退出（设备端 w()）；
 *   进度 = 已确认字节 / 文件总字节 * 100，100% 由 onSuccess 补发（设备端进度回调到不了 100）。
 */
class FirmwareUpdateActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFirmwareUpdateBinding
    private val connector: BleConnector? get() = ConnectorHolder.connector

    /** 0=准备/传输中 5=成功 6=失败 7=低电量失败（沿用设备端状态编号，勿改） */
    private var state = 0
    private var firmware: File? = null
    private var terminalJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFirmwareUpdateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null) {
            finish()
            return
        }
        val file = File(path)
        firmware = file
        if (!file.isFile) {
            Toast.makeText(this, getString(R.string.firmware_no_match), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.fwName.text = file.name
        binding.fwSize.text = getString(
            R.string.fw_size, file.length(), FirmwareManager.packetCount(file.length())
        )
        binding.fwState.text = getString(R.string.fw_reading)
        binding.fwAction.visibility = View.GONE
        binding.fwAction.setOnClickListener {
            if (state == 0) return@setOnClickListener
            if (state == 5 || state == 6 || state == 7) finish()
        }

        startTerminalFeed()
        startUpgrade(file)
    }

    /** 调试输出进页面底部终端（按用户习惯：调试信息不上弹窗） */
    private fun startTerminalFeed() {
        terminalJob = lifecycleScope.launch {
            while (true) {
                delay(150)
                val lines = DebugLog.lines.value.takeLast(12)
                binding.fwTerminal.text = lines.joinToString("\n")
            }
        }
    }

    private fun startUpgrade(file: File) {
        val c = connector
        if (c == null) {
            setState(6, getString(R.string.need_connect_first))
            return
        }
        state = 0
        binding.fwProgress.progress = 0
        binding.fwPercent.text = "0%"
        binding.fwState.text = getString(R.string.fw_state_prepare)
        binding.fwHint.text = getString(R.string.fw_hint_do_not_power_off)
        binding.fwAction.visibility = View.GONE

        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                runCatching { file.readBytes() }.getOrNull()
            }
            if (data == null) {
                setState(6, "无法读取固件文件")
                return@launch
            }
            DebugLog.log("固件: ${file.name}  ${data.size} 字节  ${FirmwareManager.packetCount(data.size.toLong())} 包")
            c.startFirmwareUpgrade(data, object : BleConnector.FirmwareUpgradeCallback {
                override fun onProgress(percent: Int, sentBytes: Int, totalBytes: Int) {
                    binding.fwProgress.progress = percent
                    binding.fwPercent.text = "$percent%"
                    binding.fwState.text = if (percent >= 100)
                        getString(R.string.fw_state_verify)
                    else
                        getString(R.string.fw_state_transfer)
                }

                override fun onSuccess() {
                    setState(5, "")
                }

                override fun onFailure(code: Int, message: String) {
                    if (code == 1) setState(7, message) else setState(6, message)
                }
            })
        }
    }

    private fun setState(newState: Int, message: String) {
        state = newState
        when (newState) {
            5 -> {
                binding.fwProgress.progress = 100
                binding.fwPercent.text = "100%"
                binding.fwState.text = getString(R.string.fw_state_success)
                binding.fwHint.text = getString(R.string.fw_hint_finished)
                binding.fwAction.text = getString(R.string.fw_action_close)
                binding.fwAction.visibility = View.VISIBLE
            }
            7 -> {
                binding.fwState.text = getString(R.string.fw_state_fail_low_power)
                binding.fwHint.text = getString(R.string.fw_hint_finished)
                binding.fwAction.text = getString(R.string.fw_action_retry)
                binding.fwAction.visibility = View.VISIBLE
                binding.fwAction.setOnClickListener {
                    firmware?.let { startUpgrade(it) }
                }
            }
            6 -> {
                binding.fwState.text = getString(R.string.fw_state_fail, message)
                binding.fwHint.text = getString(R.string.fw_hint_finished)
                binding.fwAction.text = getString(R.string.fw_action_retry)
                binding.fwAction.visibility = View.VISIBLE
                binding.fwAction.setOnClickListener {
                    firmware?.let { startUpgrade(it) }
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /** 设备端 w()：升级中（state 0）不允许返回，只提示 */
    @Deprecated("Deprecated in Java")
    @Suppress("MissingSuperCall")
    override fun onBackPressed() {
        if (state == 0) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.fw_back_blocked)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        terminalJob?.cancel()
    }

    companion object {
        const val EXTRA_PATH = "extra_firmware_path"
    }
}
