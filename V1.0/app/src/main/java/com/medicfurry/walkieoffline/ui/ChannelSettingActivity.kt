package com.medicfurry.walkieoffline.ui

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.medicfurry.walkieoffline.R
import com.medicfurry.walkieoffline.ble.BleConnector
import com.medicfurry.walkieoffline.ble.ConnectorHolder
import com.medicfurry.walkieoffline.databinding.ActivityChannelSettingBinding
import com.medicfurry.walkieoffline.databinding.ItemChannelBinding
import com.medicfurry.walkieoffline.protocol.FreqFormat
import com.medicfurry.walkieoffline.protocol.SealProtos
import kotlinx.coroutines.launch

/**
 * 频道设置页：照设备端 SharkChannelSettingActivity。
 * - 蓝色头部：当前信道名 + RX/TX 频率 + 点击编辑
 * - 自定义频道列表（0x4E29 查询设备真实数据）
 * - 预设频道（照设备端 shark/fragment/b：公众 20 + 远距离 20 两组 5 列网格，
 *   点击格子 → 0x4E27 ST_UPDATE 激活为当前频道，设备回 0x4E28 回显后高亮选中格）
 * - 自定义 tab 单击列表项 → 激活该频道（0x4E27，当前行频率蓝色高亮）；
 *   长按 → [删除/编辑/取消] 对话框（删除前二次确认）；FAB + → 新增频道
 */
class ChannelSettingActivity : BaseActivity() {

    private lateinit var binding: ActivityChannelSettingBinding
    private val connector: BleConnector? get() = ConnectorHolder.connector

    private var showCustom = true
    private val customList = mutableListOf<SealProtos.ChannelInfo>()

    /**
     * 本页绑定的信道槽位（照设备端 extra_shark_is_major_channel / shark_arg_is_major_channel）：
     * true = 主信道 ch1（0x4E27 写 ch1），false = 副信道 ch2（写 ch2）。
     * 双频模式下主页两个信道卡片各自以此参数打开，互不覆盖。
     */
    private val isMajor: Boolean by lazy { intent.getBooleanExtra(EXTRA_IS_MAJOR, true) }

    /** 该槽位在 connector.channels 中的下标：ch1=0、ch2=1 */
    private val slotIndex: Int get() = if (isMajor) 0 else 1

    // 当前槽位信道（主页频道卡片同源），用于头部回显与高亮（设备端 equals：type+no 匹配）
    private var currentCh: SealProtos.ChannelInfo? = null

    private val adapter = ChannelAdapter(
        onClick = { ch -> activateCustom(ch) },
        onLongClick = { ch, pos -> showChannelOptions(ch, pos) }
    )

    private val publicAdapter = PresetAdapter { activatePreset(it) }
    private val remoteAdapter = PresetAdapter { activatePreset(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelSettingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.channelList.layoutManager = LinearLayoutManager(this)
        binding.channelList.adapter = adapter

        // 预设两组 5 列网格（设备端 GridLayoutManager spanCount=5）
        binding.presetPublicGrid.layoutManager = GridLayoutManager(this, 5)
        binding.presetPublicGrid.adapter = publicAdapter
        binding.presetRemoteGrid.layoutManager = GridLayoutManager(this, 5)
        binding.presetRemoteGrid.adapter = remoteAdapter
        publicAdapter.submit(buildPresetChannels(SealProtos.ChType.PUBLIC))
        remoteAdapter.submit(buildPresetChannels(SealProtos.ChType.REMOTE))

        binding.btnBack.setOnClickListener { finish() }
        binding.btnEditCurrent.setOnClickListener {
            currentCh?.let { startActivity(ChannelEditActivity.intentEdit(this, it)) }
        }
        binding.fabAdd.setOnClickListener {
            // 设备端限制：自定义频道最多 20 个
            if (customList.size >= 20) {
                Toast.makeText(this, "最多 20 个自定义频道", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(ChannelEditActivity.intentAdd(this, nextFreeChannelNo()))
        }
        binding.tabCustom.setOnClickListener { selectTab(true) }
        binding.tabPreset.setOnClickListener { selectTab(false) }

        // 头部回显本槽位当前信道（主页已读到的 ch1/ch2）
        currentCh = connector?.channels?.value?.getOrNull(slotIndex)
        currentCh?.let { showCurrent(it) }
        adapter.current = currentCh
        refreshPresetHighlight()

        observeData()
        refreshList()

        // 进入页面主动查询一次设备上的自定义信道
        if (connector?.state?.value == BleConnector.State.READY) {
            connector?.sendChannelQuery()
        } else {
            Toast.makeText(this, getString(R.string.need_connect_first), Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeData() {
        lifecycleScope.launch {
            ConnectorHolder.connector?.customChannels?.collect { list ->
                customList.clear()
                customList.addAll(list)
                if (showCustom) refreshList()
            }
        }
        lifecycleScope.launch {
            ConnectorHolder.connector?.channels?.collect { list ->
                list.getOrNull(slotIndex)?.let {
                    currentCh = it
                    showCurrent(it)
                    // 当前频道行蓝色高亮（设备端 列表适配器 #0076FF）
                    adapter.current = it
                    refreshPresetHighlight()
                }
            }
        }
    }

    private fun showCurrent(ch: SealProtos.ChannelInfo) {
        binding.currentChName.text = ch.name.ifEmpty { "CH ${ch.no}" }
        binding.currentRxFreq.text = FreqFormat.format(ch.rxFreq)
        binding.currentTxFreq.text = FreqFormat.format(ch.txFreq)
    }

    private fun selectTab(custom: Boolean) {
        showCustom = custom
        binding.tabCustom.setTextColor(if (custom) 0xFF4A90D9.toInt() else 0xFF888888.toInt())
        binding.tabPreset.setTextColor(if (custom) 0xFF888888.toInt() else 0xFF4A90D9.toInt())
        binding.channelList.visibility = if (custom) View.VISIBLE else View.GONE
        binding.presetPanel.visibility = if (custom) View.GONE else View.VISIBLE
        // 设备端预设页没有新增按钮
        binding.fabAdd.visibility = if (custom) View.VISIBLE else View.GONE
        if (custom) refreshList() else refreshPresetHighlight()
    }

    private fun refreshList() = adapter.submit(customList)

    /** 找一个未占用的自定义信道号（设备端自定义信道号段从 1 开始顺序分配） */
    private fun nextFreeChannelNo(): Int {
        val used = customList.map { it.no }.toSet()
        var n = 1
        while (n in used) n++
        return n
    }

    // 注意：onResume 不再重新查询——设备端 频道列表逻辑 只在 tab 可见时查询一次，
    // 增删改全靠 0x4E2B echo 分支原位更新列表（onActivityResult 后直接 INSERT/MODIFY/DELETE，
    // 全程不重新查询设备）；重查会清空刚写入的频道并造成双重查询链交错

    // ---------- 自定义频道交互（照设备端实现） ----------

    /**
     * 单击列表项 = 激活该频道（设备端 a(View,int,SealSharkChannel) → e(channel)
     * → h0.a(channel,isMajor) → cmd 0x4E27 ChannelStateOperate{option=ST_UPDATE}）。
     * deviceMode（SHARK_MODE）3/4=扫描、5/6=组队时禁止设置；
     * 激活成功不弹 toast——设备 0x4E28 回显经 channels flow 自动更新头部与高亮（与设备端一致）。
     */
    private fun activateCustom(ch: SealProtos.ChannelInfo) {
        val c = connector
        if (c?.state?.value != BleConnector.State.READY) {
            Toast.makeText(this, getString(R.string.need_connect_first), Toast.LENGTH_SHORT).show()
            return
        }
        when (c?.deviceMode?.value ?: 0) {
            3, 4 -> {
                Toast.makeText(this, getString(R.string.scan_mode_can_not_setting), Toast.LENGTH_SHORT).show()
                return
            }
            5, 6 -> {
                Toast.makeText(this, getString(R.string.team_mode_can_not_setting), Toast.LENGTH_SHORT).show()
                return
            }
        }
        // 写入本页绑定的槽位（设备端 g.a(channel, isMajor, ST_UPDATE)：isMajor→setCh1，否则 setCh2）
        c?.sendChannelActivate(ch, isMajor = isMajor)
    }

    /** 长按列表项（设备端 b(View,int,channel)）：三选项对话框 [删除/编辑/取消]，
     *  标题含序号+名称，正文显示该频道 RX/TX 频率。
     *  注：不用 setItems——Material3 主题下普通列表项渲染塌陷（真机实测），
     *  单选列表可用但这里需要自定义行为，改用自定义视图列表（与设备端视觉一致） */
    private fun showChannelOptions(ch: SealProtos.ChannelInfo, position: Int) {
        val title = "%02d %s".format(position + 1, ch.name.ifEmpty { "CH ${ch.no}" })
        val density = resources.displayMetrics.density
        val hpad = (20 * density).toInt()
        val vpad = (14 * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(hpad, 0, hpad, 0)
            addView(TextView(context).apply {
                text = getString(R.string.channel_option_freqs,
                    FreqFormat.format(ch.rxFreq), FreqFormat.format(ch.txFreq))
                textSize = 14f
                setPadding(0, 0, 0, (6 * density).toInt())
            })
        }
        var dialog: androidx.appcompat.app.AlertDialog? = null
        val actions = listOf(
            getString(R.string.delete) to {
                dialog?.dismiss(); confirmDeleteChannel(ch)
            },
            getString(R.string.edit) to {
                dialog?.dismiss()
                startActivity(ChannelEditActivity.intentEdit(this, ch))
            },
            getString(android.R.string.cancel) to { dialog?.dismiss() }
        )
        actions.forEach { (label, action) ->
            container.addView(TextView(this).apply {
                text = label
                textSize = 16f
                setTextColor(0xFF1C1B1F.toInt())
                gravity = Gravity.CENTER_VERTICAL
                minHeight = (48 * density).toInt()
                setOnClickListener { action() }
            })
        }
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(container)
            .show()
    }

    /** 删除前二级确认（设备端同样有确认对话框）；
     *  删除结果经 0x4E2B echo 分支自动从列表移除，无需额外处理 */
    private fun confirmDeleteChannel(ch: SealProtos.ChannelInfo) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.delete_channel_confirm))
            .setPositiveButton(android.R.string.ok) { _, _ -> connector?.deleteChannel(ch) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- 预设频道（照设备端 预设频道逻辑 onCreate） ----------

    /**
     * 设备端数组（aapt2 dump 自设备端 APK）：
     * - seal_public_all_channel: 409750000 + i*12500（20 个，chType=PUBLIC=0）
     * - seal_remote_channel: 430137500/430437500 成对步进 1MHz（20 个，chType=REMOTE=1）
     * 注意 ch.no 是 0 基索引（激活时按此 chNo 下发），显示序号 = 索引+1
     */
    private fun buildPresetChannels(type: Int): List<SealProtos.ChannelInfo> {
        val freqs = if (type == SealProtos.ChType.PUBLIC) PUBLIC_FREQS else REMOTE_FREQS
        return freqs.mapIndexed { i, f ->
            SealProtos.ChannelInfo().also {
                it.no = i
                it.type = type
                it.rxFreq = f
                it.txFreq = f
                it.name = if (type == SealProtos.ChType.PUBLIC)
                    getString(R.string.preset_channel_name, i + 1)
                else
                    getString(R.string.preset_remote_channel_name, i + 1)
            }
        }
    }

    /** 点击预设格 → 激活为当前频道（设备端 h0.a(channel, isMajor=true) → cmd 0x4E27） */
    private fun activatePreset(ch: SealProtos.ChannelInfo) {
        if (connector?.state?.value != BleConnector.State.READY) {
            Toast.makeText(this, getString(R.string.need_connect_first), Toast.LENGTH_SHORT).show()
            return
        }
        if (connector?.sendChannelActivate(ch, isMajor = isMajor) == true) {
            Toast.makeText(this, getString(R.string.channel_activated, ch.name), Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshPresetHighlight() {
        publicAdapter.current = currentCh
        remoteAdapter.current = currentCh
    }

    private class ChannelAdapter(
        private val onClick: (SealProtos.ChannelInfo) -> Unit,
        private val onLongClick: (SealProtos.ChannelInfo, Int) -> Unit
    ) : RecyclerView.Adapter<ChannelAdapter.VH>() {

        private val items = mutableListOf<SealProtos.ChannelInfo>()

        /** 当前激活频道（设备端 SealSharkChannel.equals：chType+chNo 匹配），
         *  命中行 RX/TX 频率文字蓝色高亮（设备端 列表适配器 #0076FF） */
        var current: SealProtos.ChannelInfo? = null
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        fun submit(list: List<SealProtos.ChannelInfo>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        class VH(val b: ItemChannelBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ch = items[position]
            // 设备端显示列表序号（01 起），不是设备信道号
            holder.b.chTitle.text = "%02d  %s".format(position + 1, ch.name.ifEmpty { "CH ${ch.no}" })
            holder.b.chRxFreq.text = FreqFormat.format(ch.rxFreq)
            holder.b.chTxFreq.text = FreqFormat.format(ch.txFreq)
            // 当前频道行高亮（设备端 列表适配器：命中行频率文字 #0076FF，否则黑色）
            val isCurrent = current?.let { it.type == ch.type && it.no == ch.no } == true
            val color = if (isCurrent) 0xFF0076FF.toInt() else 0xFF000000.toInt()
            holder.b.chRxFreq.setTextColor(color)
            holder.b.chTxFreq.setTextColor(color)
            holder.itemView.setOnClickListener { onClick(ch) }
            holder.itemView.setOnLongClickListener {
                @Suppress("DEPRECATION")
                onLongClick(ch, holder.adapterPosition)
                true
            }
        }
    }

    /** 预设格子适配器（照设备端 b$a/b$b：仅显示序号，当前频道高亮，点击激活） */
    private class PresetAdapter(
        private val onClick: (SealProtos.ChannelInfo) -> Unit
    ) : RecyclerView.Adapter<PresetAdapter.VH>() {

        private val items = mutableListOf<SealProtos.ChannelInfo>()

        var current: SealProtos.ChannelInfo? = null
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        fun submit(list: List<SealProtos.ChannelInfo>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        class VH(val num: TextView) : RecyclerView.ViewHolder(num)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context)
                .inflate(R.layout.item_preset, parent, false) as TextView)

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ch = items[position]
            holder.num.text = (position + 1).toString()
            // 设备端 SealSharkChannel.equals：type + no 匹配即视为当前频道
            holder.num.isSelected = current?.let { it.type == ch.type && it.no == ch.no } == true
            holder.itemView.setOnClickListener { onClick(ch) }
        }
    }

    companion object {
        /** 照设备端 extra_shark_is_major_channel：true=主信道 ch1，false=副信道 ch2 */
        const val EXTRA_IS_MAJOR = "extra_shark_is_major_channel"

        /** 照设备端 SharkChannelSettingActivity.a(context, channel, isMajor) */
        fun intent(context: android.content.Context, isMajor: Boolean = true) =
            android.content.Intent(context, ChannelSettingActivity::class.java)
                .putExtra(EXTRA_IS_MAJOR, isMajor)

        // 设备端 seal_public_all_channel（aapt2 dump）：409.7500 起 12.5kHz 步进共 20 个
        private val PUBLIC_FREQS = IntArray(20) { 409_750_000 + it * 12_500 }

        // 设备端 seal_remote_channel（aapt2 dump）：430.1375/430.4375 成对，按 1MHz 步进共 20 个
        private val REMOTE_FREQS = intArrayOf(
            430_137_500, 430_437_500, 431_137_500, 431_437_500,
            432_137_500, 432_437_500, 433_137_500, 433_437_500,
            434_137_500, 434_437_500, 435_137_500, 435_437_500,
            436_137_500, 436_437_500, 437_137_500, 437_437_500,
            438_137_500, 438_437_500, 439_137_500, 439_437_500,
        )
    }
}
