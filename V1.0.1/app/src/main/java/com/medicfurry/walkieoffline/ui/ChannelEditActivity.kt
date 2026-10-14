package com.medicfurry.walkieoffline.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
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

    /** 预设频道（公众/远距离）：设备端固定数组——名称/频道号/频率只读，仅亚音可编辑 */
    private var isPreset = false
    private var rxFreq = 0
    private var txFreq = 0
    private var rxCss = 0
    private var txCss = 0

    /** 等待设备确认的操作类型（-1 = 无等待） */
    private var pendingOp = -1

    /** 用户在当前页选择的频道号（Spinner；新增默认 nextFree，编辑默认当前号） */
    private var selectedNo = -1

    /** 频道号迁移中（改号 = DELETE 旧号 + INSERT 新号；设备 MODIFY 改号会复制，严禁走 MODIFY） */
    private var migrationActive = false

    /** 迁移目标频道完整数据（含新号）：DELETE 成功后补 INSERT 用；失败时保留供重试 */
    private var migrationCh: SealProtos.ChannelInfo? = null

    /** 等待确认的超时兜底：设备未回 ACK 时解除防抖，避免按钮被永久锁定（配合防抖引入） */
    private val opTimeoutRunnable = Runnable {
        if (pendingOp >= 0) {
            if (migrationActive && pendingOp == SealProtos.ChOption.INSERT) {
                // DELETE 已确认成功、INSERT 未回确认：频道已不在设备上，保留数据弹迁移失败可重试
                showMigrateFailDialog()
            } else {
                // 其它阶段超时：状态不明，解除锁定并关页，用户重进页面即见设备真实状态
                pendingOp = -1
                migrationActive = false
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(this, getString(R.string.channel_op_failed), Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isAdd = intent.getBooleanExtra(EXTRA_IS_ADD, false)
        chNo = intent.getIntExtra(EXTRA_NO, -1)
        chType = intent.getIntExtra(EXTRA_TYPE, SealProtos.ChType.USERDEF)
        // 预设频道（公众/远距离）是设备端固定数组：名称/频道号/频率只读，仅允许编辑亚音
        // （用户明确：预设允许编辑亚音，但不允许修改频道号名称；频率同属固定数组一并锁死）。
        // 保存固定走 MODIFY（no 不变），设备端只更新该预设槽的亚音——不迁移、不插入。
        isPreset = chType == SealProtos.ChType.PUBLIC || chType == SealProtos.ChType.REMOTE
        rxFreq = intent.getIntExtra(EXTRA_RX_FREQ, 409_750_000)
        txFreq = intent.getIntExtra(EXTRA_TX_FREQ, 409_750_000)
        rxCss = intent.getIntExtra(EXTRA_RX_CSS, 0)
        txCss = intent.getIntExtra(EXTRA_TX_CSS, 0)

        binding.titleText.text = getString(
            when {
                isPreset -> R.string.preset_edit_title
                isAdd -> R.string.add_channel_title
                else -> R.string.edit_channel_title
            }
        )
        binding.nameInput.setText(intent.getStringExtra(EXTRA_NAME) ?: "")
        binding.btnDelete.visibility = if (isAdd) View.GONE else View.VISIBLE

        setupChannelNoSpinner()
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

        // 预设频道只读化：名称 / 频道号 / 收发频率锁死（频率行不挂点击），仅亚音两行可点；
        // 删除按钮隐藏（删除预设 = 破坏设备固定数组，禁止）
        if (isPreset) {
            binding.nameInput.isEnabled = false
            binding.chNoSpinner.isEnabled = false
            binding.rxFreqRow.setOnClickListener(null)
            binding.txFreqRow.setOnClickListener(null)
            binding.btnDelete.visibility = View.GONE
        }

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

    /**
     * 频道号选择：Spinner 只列「空闲槽位 ∪ {当前号}」。
     * 不开放自由输入——设备对已占用槽的 INSERT/MODIFY 是静默覆盖（真机实测），
     * 从源头杜绝写到占用槽；空闲集合取自 connector 已查询到的自定义频道表。
     */
    private fun setupChannelNoSpinner() {
        if (isPreset) {
            // 预设频道号 = 预设数组槽位（设备口径 +1 显示），与自定义槽位无关，锁定不可改
            selectedNo = chNo
            binding.chNoSpinner.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_item,
                listOf((chNo + 1).toString())
            )
            binding.chNoSpinner.setSelection(0)
            return
        }
        val used = ConnectorHolder.connector?.customChannels?.value?.map { it.no }?.toSet() ?: emptySet()
        val free = (0 until MAX_CHANNEL_SLOTS).filter { it !in used }
        val entries = if (isAdd) free else (free + chNo).distinct().sorted()
        selectedNo = when {
            isAdd && chNo in free -> chNo          // 列表页建议号仍空闲则沿用
            isAdd -> entries.firstOrNull() ?: chNo // 否则取第一个空闲槽
            else -> chNo                           // 编辑默认当前号
        }
        binding.chNoSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            // 显示用设备口径（1 起）；entries 仍是内部 0 基 no，selectedNo 取 entries[position]
            entries.map { (it + 1).toString() }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val idx = entries.indexOf(selectedNo)
        if (idx >= 0) binding.chNoSpinner.setSelection(idx)
        binding.chNoSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedNo = entries[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** 目标槽此刻是否空闲（Spinner 已保证，这里做提交前的防御性复查） */
    private fun isSlotFree(no: Int): Boolean =
        ConnectorHolder.connector?.customChannels?.value?.none { it.no == no } ?: true

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
        it.no = selectedNo
        it.type = chType
        it.rxFreq = rxFreq
        it.txFreq = txFreq
        it.name = binding.nameInput.text.toString().trim()
        it.rxCss = rxCss
        it.txCss = txCss
    }

    /**
     * 完成 → 按是否改号分三条路径（真机协议实测结论）：
     * - 新增 / 编辑未改号：原路径 INSERT / MODIFY；
     * - 编辑改号：**迁移** DELETE(旧号) → ACK → INSERT(新号)。
     *   绝不能对改号发 MODIFY——设备会按新号复制一份、旧号仍旧保留（真机实测），
     *   也不能直接 INSERT 到占用槽——设备静默覆盖销毁原频道（真机实测）。
     * 防抖：等待设备确认期间忽略重复点击。
     */
    private fun submit() {
        if (pendingOp >= 0) return
        val connector = ConnectorHolder.connector ?: return
        val ch = buildChannel()
        // 预设频道号锁定：防御性保证 no == chNo，走 MODIFY（只改亚音），绝不迁移/插入
        if (isPreset) ch.no = chNo
        when {
            isAdd -> {
                if (!isSlotFree(ch.no)) {
                    Toast.makeText(this, getString(R.string.channel_no_occupied), Toast.LENGTH_SHORT).show()
                    return
                }
                pendingOp = SealProtos.ChOption.INSERT
                if (!connector.insertChannel(ch)) {
                    pendingOp = -1
                    Toast.makeText(this, getString(R.string.channel_op_failed), Toast.LENGTH_SHORT).show()
                } else {
                    armOpTimeout()
                }
            }
            ch.no != chNo -> {
                // 改号 = 迁移：先删旧号（Spinner 已保证新号空闲，这里防御性复查）
                if (!isSlotFree(ch.no)) {
                    Toast.makeText(this, getString(R.string.channel_no_occupied), Toast.LENGTH_SHORT).show()
                    return
                }
                migrationActive = true
                migrationCh = ch
                pendingOp = SealProtos.ChOption.DELETE
                val delCh = SealProtos.ChannelInfo().also {
                    it.no = chNo
                    it.type = chType
                }
                if (!connector.deleteChannel(delCh)) {
                    migrationActive = false
                    pendingOp = -1
                    Toast.makeText(this, getString(R.string.channel_op_failed), Toast.LENGTH_SHORT).show()
                } else {
                    armOpTimeout()
                }
            }
            else -> {
                pendingOp = SealProtos.ChOption.MODIFY
                if (!connector.modifyChannel(ch)) {
                    pendingOp = -1
                    Toast.makeText(this, getString(R.string.channel_op_failed), Toast.LENGTH_SHORT).show()
                } else {
                    armOpTimeout()
                }
            }
        }
    }

    /** 迁移失败对话框：DELETE 已成功、INSERT 失败，原频道数据保留在 migrationCh，可只补 INSERT 重试 */
    private fun showMigrateFailDialog() {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.channel_migrate_failed_title)
            .setMessage(R.string.channel_migrate_failed_msg)
            .setPositiveButton(R.string.retry) { _, _ ->
                val connector = ConnectorHolder.connector ?: return@setPositiveButton
                val ch = migrationCh ?: return@setPositiveButton
                pendingOp = SealProtos.ChOption.INSERT
                if (!connector.insertChannel(ch)) {
                    pendingOp = -1
                    Toast.makeText(this, getString(R.string.channel_op_failed), Toast.LENGTH_SHORT).show()
                } else {
                    armOpTimeout()
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                // 取消：关页让用户看设备真实状态（频道已不在旧号）
                migrationActive = false
                pendingOp = -1
                finish()
            }
            .setCancelable(false)
            .show()
    }

    /** 发出操作后启动确认超时兜底（10s 未回 ACK 则解除按钮锁定） */
    private fun armOpTimeout() {
        binding.root.removeCallbacks(opTimeoutRunnable)
        binding.root.postDelayed(opTimeoutRunnable, 10_000L)
    }

    private fun confirmDelete() {
        if (pendingOp >= 0) return   // 防抖：等待设备确认期间忽略重复点击
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.delete_channel_confirm))
            .setPositiveButton(R.string.delete) { _, _ ->
                val connector = ConnectorHolder.connector ?: return@setPositiveButton
                pendingOp = SealProtos.ChOption.DELETE
                if (!connector.deleteChannel(buildChannel())) {
                    pendingOp = -1
                    Toast.makeText(this, getString(R.string.channel_op_failed), Toast.LENGTH_SHORT).show()
                } else {
                    armOpTimeout()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 等设备的 ChannelInfoOperate 响应（0x4E2B 带 result），CH_OK=0 成功。
     *  迁移流程（改号）占两步：DELETE 确认成功 → 自动补 INSERT；INSERT 失败 → 迁移失败对话框（可只补 INSERT 重试） */
    private fun observeOpResult() {
        lifecycleScope.launch {
            ConnectorHolder.connector?.channelOpResult?.collect { op ->
                if (op == null || pendingOp < 0) return@collect
                ConnectorHolder.connector?.consumeChannelOpResult()
                binding.root.removeCallbacks(opTimeoutRunnable)   // 确认已到，撤销超时兜底
                val success = op.result == 0
                if (migrationActive) {
                    onMigrationAck(success)
                    return@collect
                }
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

    /** 迁移 ACK 状态机：DELETE 成功 → 补 INSERT；INSERT 成功 → 收尾；INSERT 失败 → 保留数据弹重试 */
    private fun onMigrationAck(success: Boolean) {
        when (pendingOp) {
            SealProtos.ChOption.DELETE -> {
                if (!success) {
                    // 旧号都没删掉：数据未动，按普通失败处理
                    migrationActive = false
                    pendingOp = -1
                    Toast.makeText(this, getString(R.string.channel_op_failed), Toast.LENGTH_SHORT).show()
                    return
                }
                val connector = ConnectorHolder.connector
                val ch = migrationCh
                if (connector == null || ch == null) {
                    migrationActive = false
                    pendingOp = -1
                    Toast.makeText(this, getString(R.string.channel_op_failed), Toast.LENGTH_SHORT).show()
                    return
                }
                // 旧号已删，立即补写新号（原频道完整数据在 ch 中）。
                // 必须 post 到下一个消息循环再发：0x4E2B 结果经 StateFlow 派发时是在
                // handleChannel 内部重入执行的，此刻 connector 的 channelOpPending
                // 防抖标志尚未清除（finishChannelOp 在派发之后才跑），
                // 同步重发会被防抖误拒。post 后标志已清除，可正常下发。
                pendingOp = SealProtos.ChOption.INSERT
                binding.root.post {
                    if (isFinishing || isDestroyed) return@post
                    val c = ConnectorHolder.connector
                    if (c == null || !c.insertChannel(ch)) {
                        showMigrateFailDialog()
                    } else {
                        armOpTimeout()
                    }
                }
            }
            SealProtos.ChOption.INSERT -> {
                if (success) {
                    migrationActive = false
                    pendingOp = -1
                    Toast.makeText(this, getString(R.string.channel_op_success), Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    // 新号写入失败：频道已不在设备上，保留数据供重试
                    showMigrateFailDialog()
                }
            }
        }
    }

    override fun onDestroy() {
        // 清理防抖超时回调，避免页面销毁后残留
        if (::binding.isInitialized) binding.root.removeCallbacks(opTimeoutRunnable)
        super.onDestroy()
    }

    companion object {
        /** 设备自定义信道槽位数（真机实测：no 0..19，超出 result=1 拒绝） */
        const val MAX_CHANNEL_SLOTS = 20

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
