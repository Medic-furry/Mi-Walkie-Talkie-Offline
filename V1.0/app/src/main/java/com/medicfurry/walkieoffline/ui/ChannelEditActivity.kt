package com.medicfurry.walkieoffline.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.lifecycleScope
import com.medicfurry.walkieoffline.R
import com.medicfurry.walkieoffline.ble.ConnectorHolder
import com.medicfurry.walkieoffline.databinding.ActivityChannelEditBinding
import com.medicfurry.walkieoffline.protocol.CssTones
import com.medicfurry.walkieoffline.protocol.FreqFormat
import com.medicfurry.walkieoffline.protocol.SealProtos
import kotlinx.coroutines.launch

/**
 * 频道编辑页：照设备端 频道编辑逻辑。
 * 名称 / 接收频率 / 发射频率 / 接收亚音 / 发射亚音 / 删除 / 完成。
 * 完成 → 0x4E2A（MODIFY 或 INSERT）；删除 → 0x4E2A（DELETE）。
 */
class ChannelEditActivity : BaseActivity() {

    private lateinit var binding: ActivityChannelEditBinding
    private var isAdd = false

    private var chNo = -1
    private var chType = SealProtos.ChType.USERDEF
    private var rxFreq = 0
    private var txFreq = 0
    private var rxCss = 0
    private var txCss = 0

    /** 等待设备确认的操作类型（-1 = 无等待） */
    private var pendingOp = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isAdd = intent.getBooleanExtra(EXTRA_IS_ADD, false)
        chNo = intent.getIntExtra(EXTRA_NO, -1)
        chType = intent.getIntExtra(EXTRA_TYPE, SealProtos.ChType.USERDEF)
        rxFreq = intent.getIntExtra(EXTRA_RX_FREQ, 409_750_000)
        txFreq = intent.getIntExtra(EXTRA_TX_FREQ, 409_750_000)
        rxCss = intent.getIntExtra(EXTRA_RX_CSS, 0)
        txCss = intent.getIntExtra(EXTRA_TX_CSS, 0)

        binding.titleText.text = getString(
            if (isAdd) R.string.add_channel_title else R.string.edit_channel_title
        )
        binding.nameInput.setText(intent.getStringExtra(EXTRA_NAME) ?: "")
        binding.btnDelete.visibility = if (isAdd) View.GONE else View.VISIBLE

        refreshRows()

        binding.btnBack.setOnClickListener { finish() }
        binding.rxFreqRow.setOnClickListener {
            showFreqDialog(getString(R.string.frequency_rx), rxFreq) { rxFreq = it; refreshRows() }
        }
        binding.txFreqRow.setOnClickListener {
            showFreqDialog(getString(R.string.frequency_tx), txFreq) { txFreq = it; refreshRows() }
        }
        binding.rxCssRow.setOnClickListener { showCssDialog(rxCss) { rxCss = it; refreshRows() } }
        binding.txCssRow.setOnClickListener { showCssDialog(txCss) { txCss = it; refreshRows() } }

        binding.btnDone.setOnClickListener { submit() }
        binding.btnDelete.setOnClickListener { confirmDelete() }

        observeOpResult()
    }

    private fun refreshRows() {
        binding.rxFreqValue.text = FreqFormat.format(rxFreq)
        binding.txFreqValue.text = FreqFormat.format(txFreq)
        binding.rxCssValue.text = CssTones.toDisplay(rxCss)
        binding.txCssValue.text = CssTones.toDisplay(txCss)
    }

    /** 频率输入对话框（照设备端：标题 + 输入框 + 取消/确定） */
    private fun showFreqDialog(title: String, current: Int, onSet: (Int) -> Unit) {
        val input = EditText(this).apply {
            setText(FreqFormat.format(current))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSelection(text.length)
        }
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()

        // 输入框 + 红色范围警告（未刷机固件只支持 400-470MHz）
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        container.addView(input)
        val warnRaw = getString(R.string.freq_range_warning)
        val warnText = TextView(this).apply {
            text = warnRaw.replace("|", "")
            setTextColor(0xFFD32F2F.toInt())   // 红色警告
            textSize = 14f
            setLineSpacing(0f, 1.2f)
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        container.addView(warnText)

        val scroll = MaxHeightScrollView(this).apply {
            maxHeightPx = (resources.displayMetrics.heightPixels * 0.6f).toInt()
            isVerticalScrollBarEnabled = false
        }
        scroll.addView(container)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(R.string.done) { _, _ ->
                val hz = parseFreq(input.text.toString())
                if (hz <= 0) {
                    Toast.makeText(this, getString(R.string.invalid_freq), Toast.LENGTH_LONG).show()
                } else {
                    onSet(hz)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()

        // 完成：蓝色（与刷机弹窗确认按钮一致）
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            .setTextColor(0xFF0076FF.toInt())

        // 布局完成后按可用宽度断行（不拆词、不留单字行）
        TextLayout.wrapBlocks(warnText, warnRaw.split("|"))
    }

    /** 解析 "438.7125" → 438712500 Hz（最多 4 位小数） */
    private fun parseFreq(text: String): Int {
        val t = text.trim()
        if (!t.matches(Regex("""\d{3}\.\d{1,4}"""))) return -1
        val mhz = t.toDoubleOrNull() ?: return -1
        if (mhz < 100.0 || mhz > 1000.0) return -1
        return (mhz * 1_000_000).toInt()
    }

    /** 亚音选择对话框（模拟 51 + 数字正码 104 + 数字反码 104） */
    private fun showCssDialog(current: Int, onSet: (Int) -> Unit) {
        val entries = CssTones.pickerEntries
        val names = entries.map { it.first }.toTypedArray()
        val currentIdx = entries.indexOfFirst { it.second == current }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setSingleChoiceItems(names, currentIdx) { dialog, which ->
                onSet(entries[which].second)
                dialog.dismiss()
            }
            .show()
    }

    private fun buildChannel(): SealProtos.ChannelInfo = SealProtos.ChannelInfo().also {
        it.no = chNo
        it.type = chType
        it.rxFreq = rxFreq
        it.txFreq = txFreq
        it.name = binding.nameInput.text.toString().trim()
        it.rxCss = rxCss
        it.txCss = txCss
    }

    /** 完成 → 下发 MODIFY / INSERT（设备端 h0.c() / h0.b()） */
    private fun submit() {
        val connector = ConnectorHolder.connector ?: return
        val ch = buildChannel()
        pendingOp = if (isAdd) SealProtos.ChOption.INSERT else SealProtos.ChOption.MODIFY
        val ok = if (isAdd) connector.insertChannel(ch) else connector.modifyChannel(ch)
        if (!ok) {
            pendingOp = -1
            Toast.makeText(this, getString(R.string.channel_op_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.delete_channel_confirm))
            .setPositiveButton(R.string.delete) { _, _ ->
                val connector = ConnectorHolder.connector ?: return@setPositiveButton
                pendingOp = SealProtos.ChOption.DELETE
                if (!connector.deleteChannel(buildChannel())) {
                    pendingOp = -1
                    Toast.makeText(this, getString(R.string.channel_op_failed), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 等设备的 ChannelInfoOperate 响应（0x4E2B 带 result），CH_OK=0 成功 */
    private fun observeOpResult() {
        lifecycleScope.launch {
            ConnectorHolder.connector?.channelOpResult?.collect { op ->
                if (op == null || pendingOp < 0) return@collect
                ConnectorHolder.connector?.consumeChannelOpResult()
                val success = op.result == 0
                Toast.makeText(
                    this@ChannelEditActivity,
                    getString(if (success) R.string.channel_op_success else R.string.channel_op_failed),
                    Toast.LENGTH_SHORT
                ).show()
                pendingOp = -1
                if (success) finish()
            }
        }
    }

    companion object {
        private const val EXTRA_IS_ADD = "is_add"
        private const val EXTRA_NO = "no"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_RX_FREQ = "rx_freq"
        private const val EXTRA_TX_FREQ = "tx_freq"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_RX_CSS = "rx_css"
        private const val EXTRA_TX_CSS = "tx_css"

        fun intentEdit(context: Context, ch: SealProtos.ChannelInfo): Intent =
            Intent(context, ChannelEditActivity::class.java).apply {
                putExtra(EXTRA_IS_ADD, false)
                putExtra(EXTRA_NO, ch.no)
                putExtra(EXTRA_TYPE, if (ch.type >= 0) ch.type else SealProtos.ChType.USERDEF)
                putExtra(EXTRA_RX_FREQ, ch.rxFreq)
                putExtra(EXTRA_TX_FREQ, ch.txFreq)
                putExtra(EXTRA_NAME, ch.name)
                putExtra(EXTRA_RX_CSS, if (ch.rxCss >= 0) ch.rxCss else 0)
                putExtra(EXTRA_TX_CSS, if (ch.txCss >= 0) ch.txCss else 0)
            }

        fun intentAdd(context: Context, chNo: Int): Intent =
            Intent(context, ChannelEditActivity::class.java).apply {
                putExtra(EXTRA_IS_ADD, true)
                putExtra(EXTRA_NO, chNo)
                putExtra(EXTRA_TYPE, SealProtos.ChType.USERDEF)
                putExtra(EXTRA_RX_FREQ, 409_750_000)
                putExtra(EXTRA_TX_FREQ, 409_750_000)
            }
    }
}
