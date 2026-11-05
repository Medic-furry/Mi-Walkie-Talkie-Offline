package com.medicfurry.walkieoffline.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager
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

    /**
     * FRS 频道（1.0.5+M1 固件打开的隐藏页，type=3）：频率来自固件表（同预设锁死、可删/迁移禁用），
     * 但名称可编辑（1.0.5+M1 对接文档 §3.2），存储在 NVM h=0x14。
     */
    private var isFrs = false
    private var rxFreq = 0
    private var txFreq = 0
    private var rxCss = 0
    private var txCss = 0

    /** 设备侧回读的每频道静噪/VOX（协议 field 7/8）：编辑回写时原样透传，
     *  避免 MODIFY 把设备已存的值抹掉；未回读（-1）则不发送，与官方 APP 行为一致。
     *  （1.0.5+M1 对接文档 §5.2：每频道 sq 生效性待真机验证，透传不引入新语义） */
    private var chSq = -1
    private var chVox = -1

    /** 等待设备确认的操作类型（-1 = 无等待） */
    private var pendingOp = -1

    /** 用户在当前页选择的频道号（Spinner；新增默认 nextFree，编辑默认当前号） */
    private var selectedNo = -1

    /** 频道号迁移中（改号 = DELETE 旧号 + INSERT 新号；设备 MODIFY 改号会复制，严禁走 MODIFY） */
    private var migrationActive = false

    /** 迁移目标频道完整数据（含新号）：DELETE 成功后补 INSERT 用；失败时保留供重试 */
    private var migrationCh: SealProtos.ChannelInfo? = null

    /** 迁移前置切台的延迟执行体：激活切换的 0x4E28 回显到达后由 observeOpResult 触发 */
    private var pendingOpAfterSwitch: (() -> Unit)? = null

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
        // FRS 频道：频率/频道号锁死（来自固件表），名称可编辑（与预设的唯一区别）
        // type=9 = FRS 预设（北美家用频道，锁频道号/频率）；type=3 = 1.0.5+M1 自定义频道表（可编辑）
        isFrs = chType == SealProtos.ChType.FRS_RECORD
        rxFreq = intent.getIntExtra(EXTRA_RX_FREQ, 409_750_000)
        txFreq = intent.getIntExtra(EXTRA_TX_FREQ, 409_750_000)
        rxCss = intent.getIntExtra(EXTRA_RX_CSS, 0)
        txCss = intent.getIntExtra(EXTRA_TX_CSS, 0)
        chSq = intent.getIntExtra(EXTRA_SQ, -1)
        chVox = intent.getIntExtra(EXTRA_VOX, -1)

        binding.titleText.text = getString(
            when {
                isPreset -> R.string.preset_edit_title
                isFrs -> R.string.frs_edit_title
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

        // 预设/FRS 频道只读化：频道号 / 收发频率锁死（频率行不挂点击），删除按钮隐藏
        // （删除预设/FRS = 破坏设备固定数组，禁止）；FRS 的名称保持可编辑（isPreset 才锁名称）
        if (isPreset || isFrs) {
            binding.chNoRow.isEnabled = false
            binding.chNoRow.alpha = 0.5f
            binding.rxFreqRow.setOnClickListener(null)
            binding.txFreqRow.setOnClickListener(null)
            binding.btnDelete.visibility = View.GONE
        }
        if (isPreset) {
            binding.nameInput.isEnabled = false
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
        // 提示文本跟随设备容量（指纹判定 29/20 或 About 手动覆盖后的 MAX_CHANNEL_SLOTS）
        binding.chNoHint.text = getString(R.string.channel_no_range, MAX_CHANNEL_SLOTS)
        if (isPreset || isFrs) {
            // 预设/FRS 频道号 = 设备固定槽位（设备口径 +1 显示），与自定义槽位无关，锁定不可改
            selectedNo = chNo
            binding.chNoValue.text = (chNo + 1).toString()
            binding.chNoRow.isEnabled = false
            binding.chNoRow.alpha = 0.5f
            return
        }
        // 自定义频道：初始号 = 列表页建议号（新增）或当前号（编辑），宫格里可改选
        selectedNo = chNo
        refreshChannelNoDisplay()
        binding.chNoRow.setOnClickListener { showChannelNoGridDialog() }
    }

    /** 刷新频道号显示（设备口径 +1） */
    private fun refreshChannelNoDisplay() {
        binding.chNoValue.text = (selectedNo + 1).toString()
    }

    /**
     * 频道号宫格弹窗：与预设频道同款 5 列宫格（item_preset），1..容量逐格显示。
     * 占用槽灰显 35% 不可点（设备对已占用槽的写入是静默覆盖，从源头杜绝），
     * 空闲槽可点，点击即选定并关闭。当前号高亮（预设选中态同款青绿底）。
     */
    private fun showChannelNoGridDialog() {
        val used = ConnectorHolder.connector?.customChannels?.value
            ?.filter { it.no != chNo || isAdd }   // 编辑时当前号不算占用
            ?.associateBy { it.no } ?: emptyMap()
        var dialog: androidx.appcompat.app.AlertDialog? = null
        val recycler = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@ChannelEditActivity, 5)
            adapter = object : RecyclerView.Adapter<GridCellVH>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GridCellVH =
                    GridCellVH(LayoutInflater.from(parent.context)
                        .inflate(R.layout.item_preset, parent, false) as TextView)

                override fun getItemCount() = MAX_CHANNEL_SLOTS

                override fun onBindViewHolder(holder: GridCellVH, position: Int) {
                    val no = position
                    val ch = used[no]
                    val occupied = ch != null && ch.isValid
                    holder.tv.text = (no + 1).toString()
                    holder.tv.isSelected = !occupied && no == selectedNo
                    if (occupied) {
                        // 占用槽：灰显 35%，不可点（设备对已占用槽写入是静默覆盖）
                        holder.tv.alpha = 0.35f
                        holder.tv.setOnClickListener(null)
                    } else {
                        holder.tv.alpha = 1f
                        holder.tv.setOnClickListener {
                            selectedNo = no
                            refreshChannelNoDisplay()
                            dialog?.dismiss()
                        }
                    }
                }
            }
        }
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.channel_number_label)
            .setView(recycler)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 宫格单元 ViewHolder（item_preset 同款格子） */
    private inner class GridCellVH(val tv: TextView) : RecyclerView.ViewHolder(tv)

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
        // 每频道 sq/vox 透传（仅当设备回读携带正值时；-1/0 不发送，保持官方 APP 行为）
        if (chSq > 0) it.sq = chSq
        if (chVox > 0) it.vox = chVox
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
        // 预设/FRS 频道号锁定：防御性保证 no == chNo，走 MODIFY（不改号），绝不迁移/插入
        if (isPreset || isFrs) ch.no = chNo
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
                // 改号 = 迁移：先删旧号（宫格已保证新号空闲，这里防御性复查）
                if (!isSlotFree(ch.no)) {
                    Toast.makeText(this, getString(R.string.channel_no_occupied), Toast.LENGTH_SHORT).show()
                    return
                }
                // 设备禁止删除"当前激活中"的频道（DELETE → result=3 FORBIDE，真机实测）：
                // 待删频道是激活频道时，先切到替身频道，等 0x4E28 回显后再执行迁移删除
                if (isCurrentlyActive(chNo, chType)) {
                    val substitute = pickSubstituteChannel(chNo)
                    connector.sendChannelActivate(substitute, isMajor = true)
                    pendingOp = SealProtos.ChOption.DELETE
                    pendingOpAfterSwitch = { performMigrationDelete(ch) }
                    armOpTimeout()
                    return
                }
                performMigrationDelete(ch)
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

    /** 迁移步骤一：DELETE 旧号（激活频道预切走完成后的实际执行） */
    private fun performMigrationDelete(ch: SealProtos.ChannelInfo) {
        val connector = ConnectorHolder.connector ?: return
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

    /** 该频道是否为当前激活频道（ch1/ch2 按 type+no 匹配，与设备端 SealSharkChannel.equals 同口径） */
    private fun isCurrentlyActive(no: Int, type: Int): Boolean =
        ConnectorHolder.connector?.channels?.value
            ?.any { it.type == type && it.no == no } == true

    /** 迁移/删除激活频道时的替身：列表里第一條其他自定义频道，否则公众频道 1 */
    private fun pickSubstituteChannel(excludeNo: Int): SealProtos.ChannelInfo =
        ConnectorHolder.connector?.customChannels?.value
            ?.firstOrNull { it.no != excludeNo && it.type == chType }
            ?: SealProtos.ChannelInfo().also {
                it.no = 0
                it.type = SealProtos.ChType.PUBLIC
                it.name = getString(R.string.preset_channel_name, 1)
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
                // 激活频道需先切走（设备禁止删除激活中的频道：DELETE → result=3 FORBIDE）
                if (isCurrentlyActive(chNo, chType)) {
                    connector.sendChannelActivate(pickSubstituteChannel(chNo), isMajor = true)
                    binding.root.postDelayed({
                        pendingOp = SealProtos.ChOption.DELETE
                        if (!connector.deleteChannel(buildChannel())) {
                            pendingOp = -1
                            Toast.makeText(this, getString(R.string.channel_op_failed), Toast.LENGTH_SHORT).show()
                        } else {
                            armOpTimeout()
                        }
                    }, 800)
                } else {
                    pendingOp = SealProtos.ChOption.DELETE
                    if (!connector.deleteChannel(buildChannel())) {
                        pendingOp = -1
                        Toast.makeText(this, getString(R.string.channel_op_failed), Toast.LENGTH_SHORT).show()
                    } else {
                        armOpTimeout()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 等设备的 ChannelInfoOperate 响应（0x4E2B 带 result），CH_OK=0 成功。
     *  迁移流程（改号）占两步：DELETE 确认成功 → 自动补 INSERT；INSERT 失败 → 迁移失败对话框（可只补 INSERT 重试） */
    private fun observeOpResult() {
        lifecycleScope.launch {
            // 迁移前置切台的 0x4E28 回显：到达即执行挂起的迁移 DELETE
            ConnectorHolder.connector?.channels?.collect { list ->
                val pending = pendingOpAfterSwitch ?: return@collect
                // 切台已生效（激活槽回显更新）→ 清除挂起并执行
                pendingOpAfterSwitch = null
                pendingOp = -1
                binding.root.postDelayed({ pending?.invoke() }, 600)
            }
        }
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
                    // 迁移完成：把重编号后的频道切为激活（改号的就是用户在用的频道）
                    migrationCh?.let {
                        ConnectorHolder.connector?.sendChannelActivate(it, isMajor = true)
                    }
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
        /**
         * 自定义信道槽位数上限（0 基 no 的开区间上界）——**动态值**：
         * 下限 29（1.0.5+M1 固件上限，desc 容量 59 槽硬顶），随设备实读自动上浮
         * （ChannelSettingActivity 在自定义列表同步后按 max(no)+1 扩展），
         * 50/100 条等任意固件无需改 App。真正的写边界由设备把守：
         * 写 no≥设备上限时设备回 result=ERROR 拒绝（官方固件 =20 即此行为）。
         */
        var MAX_CHANNEL_SLOTS = 29
            private set

        /**
         * 设备容量判定后同步槽位上限（1.0.5+M1=29 / 官方=20），由 ChannelSettingActivity
         * 在 BleConnector 指纹判定后调用；新增/编辑/整理视图全部按此容量展开。
         */
        fun setCapacitySlots(cap: Int) {
            if (cap in 20..200) MAX_CHANNEL_SLOTS = cap
        }

        private const val EXTRA_IS_ADD = "is_add"
        private const val EXTRA_NO = "no"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_RX_FREQ = "rx_freq"
        private const val EXTRA_TX_FREQ = "tx_freq"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_RX_CSS = "rx_css"
        private const val EXTRA_TX_CSS = "tx_css"
        private const val EXTRA_SQ = "sq"
        private const val EXTRA_VOX = "vox"

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
                putExtra(EXTRA_SQ, ch.sq)
                putExtra(EXTRA_VOX, ch.vox)
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
