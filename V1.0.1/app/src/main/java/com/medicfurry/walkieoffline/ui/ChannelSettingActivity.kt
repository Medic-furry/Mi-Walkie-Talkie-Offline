package com.medicfurry.walkieoffline.ui

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.HapticFeedbackConstants
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.medicfurry.walkieoffline.R
import com.medicfurry.walkieoffline.ble.BleConnector
import com.medicfurry.walkieoffline.ble.ConnectorHolder
import com.medicfurry.walkieoffline.databinding.ActivityChannelSettingBinding
import com.medicfurry.walkieoffline.databinding.ItemChannelBinding
import com.medicfurry.walkieoffline.debug.DebugLog
import com.medicfurry.walkieoffline.protocol.FreqFormat
import com.medicfurry.walkieoffline.protocol.SealProtos
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 空槽占位行的哨兵 type：设备 ChType 为 0/1/2（PUBLIC/REMOTE/USERDEF），
 * 用 -2 表示「这一行是空槽占位」，永远不会被写进设备。
 * 占位行 rxFreq 保持 -1，connector 的 isValid（rxFreq>0）校验天然排除它。
 */
private const val SLOT_PLACEHOLDER = -2

/**
 * 迁移步骤的「不删除」哨兵：批量整理是「先腾空再按目标顺序插入」，
 * 插入步骤本身不需要再删任何槽，用 -1 表示跳过删除阶段。
 */
private const val NO_DELETE = -1

/** 该列表项是否是空槽占位行（拖拽开始时插入列表，作为可放置目标） */
private fun SealProtos.ChannelInfo.isSlotPlaceholder(): Boolean = type == SLOT_PLACEHOLDER

/** 长按把手多久才启动拖拽（防误触）：用户实测把手太小、滑动时容易误触发拖动。
 *  原 2000ms 用户反馈"等太久"，改为 1000ms（仍远大于系统 400ms 长按阈值，防误触保留） */
private const val DRAG_HOLD_MS = 1000L

/** 长按等待期间手指允许晃动的半径（dp）：超过就当成滚动手势、取消起拖 */
private const val DRAG_HOLD_CANCEL_SLOP_DP = 24f

/** 让位平移过渡时长（ms）：卡片分开/合上用缓动动画，避免反复抽动 */
private const val GAP_ANIM_MS = 200L

/**
 * 启拖时「从按压点扩散到整卡」的波纹时长（ms）：卡片 alpha 由 1 渐变到 [ChannelSettingActivity]
 * 的 DRAG_ALPHA，同时以按压点为圆心的圆扩散覆盖整张卡片（见 [ChannelSettingActivity.startDragReveal]）。
 * 取 260ms ≈ 让位动画 [GAP_ANIM_MS] 的同量级，看起来像"卡片被拎起来"而不是瞬间闪一下。
 */
private const val DRAG_REVEAL_MS = 260L

/**
 * 让位缓冲量（dp）：起拖位的**上下相邻行**各再往外撑开这么多，
 * 视觉上"给被拖卡片让出位置"（用户原话："拖 15 时 14 上移、16 下移"）。
 * 只撑开一点（12dp）不顶开整行——真正的让位由整行平移负责，这一层只是让分离更"有弹性"。
 */
private const val DRAG_PEEK_DP = 12f

/** 插入指示框位置/透明度的逐帧缓动跟随系数（0..1，越大跟得越紧） */
private const val INSERT_BOX_FOLLOW = 0.3f

/**
 * 让位预览描述：让位区间 + 平移量 + 撑开缓冲。适配器 onBindViewHolder 据此给新绑定的行补位移
 * （拖拽自动滚动时区间内的行可能被回收重绑，不补位移缺口会断）。
 *
 * [shiftFor] 是唯一的偏移计算入口——[ChannelSettingActivity.applyGapPreview]（带动画）与
 * [ChannelAdapter.onBindViewHolder]（回收重绑直接补位移）都用它，两条路径算出来的偏移
 * 必然一致，缺口不会在自动滚动时错开（曾经两份公式各写一遍，滚动后缺口会抖）。
 */
private class GapPreview(
    val fromPos: Int,
    /** 落点位；[RecyclerView.NO_POSITION] = 还没形成落点缺口（刚拎起来、或落点为空槽） */
    val hoverPos: Int,
    val shift: Float,
    /** 撑开缓冲的锚点（= 起拖位）：它上下相邻的两行各再撑开 [peekPx] */
    val peekFrom: Int,
    /** 撑开缓冲量（px），0 = 不做缓冲 */
    val peekPx: Float
) {
    /**
     * 某一行在本次让位预览下的目标纵向偏移（px，正 = 下移）：
     * - 有落点缺口时：区间内的行整体平移一个卡片高度，腾出缺口（真正的"让开位置"）；
     * - 紧邻 [peekFrom] 的上下两行：各再撑开 [peekPx]，视觉上"卡片被拎起来、两边让一让"
     *   （用户："拖 15 时 14 上移、16 下移"）。**区间内的行不叠 peek** —— 叠了会把刚腾出
     *   的缺口填回去，落点判定与缺口高度都会失真。
     */
    fun shiftFor(pos: Int): Float {
        if (hoverPos != RecyclerView.NO_POSITION) {
            val down = hoverPos > fromPos
            val inRange =
                if (down) pos in (fromPos + 1)..hoverPos else pos in hoverPos until fromPos
            if (inRange) return if (down) -shift else shift
        }
        if (peekFrom == RecyclerView.NO_POSITION || peekPx == 0f) return 0f
        return when (pos) {
            peekFrom - 1 -> -peekPx
            peekFrom + 1 -> peekPx
            else -> 0f
        }
    }
}

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

    /** 排序链路诊断日志（同时进 logcat 与主页「调试终端」，便于真机核对拖拽落点） */
    private fun log(msg: String) = DebugLog.i("ChannelSettingActivity", msg)

    // 当前槽位信道（主页频道卡片同源），用于头部回显与高亮（设备端 equals：type+no 匹配）
    private var currentCh: SealProtos.ChannelInfo? = null

    /** 本页已下发删除、等待设备确认（用于区分本页删除与编辑页增删改的结果事件） */
    private var pendingDeleteUi = false

    /** 删除确认超时兜底：设备未回 ACK 时复位，避免结果事件误挂到后续操作 */
    private val deleteUiTimeoutRunnable = Runnable { pendingDeleteUi = false }

    /** 对话框关闭后的短时点击屏蔽：急躁连点的余误触会落到列表行、被当成单击激活频道
     *  （QA 实测 6 连点 = 5 次真实 0x4E27 激活，会把当前频道切走） */
    private var listClickGuard = false

    /** 屏蔽窗口结束回调：每次屏蔽都有固定上限，停手后自动恢复，不会长期屏蔽正常单击 */
    private val listClickGuardRunnable = Runnable { listClickGuard = false }

    // ---------- 拖拽排序（仅自定义 tab） ----------

    /** 排序执行中：屏蔽再次拖拽与其它增删改，进度对话框模态展示 */
    private var reorderActive = false

    /** 用户点了进度框「取消」：在当前步骤完成后的安全点停止 */
    private var reorderCancel = false

    /** 槽位拖拽中：列表已展开为 20 槽视图（含空槽占位行），松手后据此计算「旧号→新号」 */
    private var slotDragActive = false

    /** 本次槽位拖拽的频道原号：展开成 20 槽视图后用它定位到新位置再启动拖拽 */
    private var slotDragNo = -1

    /** 正在被拖的 ViewHolder：用于松手后把半透明恢复成不透明（避免残留半透明卡片） */
    private var dragActiveVh: RecyclerView.ViewHolder? = null

    /** 当前蓝色高亮的悬停落点行（只有满足 canDropOver 的行才会被点亮，见 [refreshHoverTarget]） */
    private var dragHoverVh: RecyclerView.ViewHolder? = null

    /** 蓝色落点行的适配器下标：松手后按它做换位（onSelectedChanged 复位会清掉 [dragHoverVh]，故单独留存） */
    private var dragHoverPos: Int = RecyclerView.NO_POSITION

    /**
     * 起拖时手指在「列表坐标系」里的 y。配合 ItemTouchHelper 回调的 dY 即可算出手指当前
     * 位置（fingerY = 本值 + dY）——判定「拖出列表 = 取消」必须用真实手指位置，不能用被拖
     * 卡片的位置：拖到列表边缘时 ItemTouchHelper 会自动滚动列表，卡片被滚动带着走、与手指
     * 脱节（实测：甩到底立即松手时卡片仍在可视区内，于是照常换位写入；要停留几秒等列表滚到
     * 尽头才取消）。dY 是 ItemTouchHelper 内部用来跟手的量，不受滚动影响。
     *
     * 为什么不能用监听器取手指坐标（两条都实测失败）：
     * - setOnTouchListener：RecyclerView 是 ViewGroup，ViewGroup#dispatchTouchEvent 不回调
     *   View 的 OnTouchListener，挂上去一个事件都收不到（lastTouchY 恒为 0）。
     * - addOnItemTouchListener：拖拽开始后事件只派发给 active listener（ItemTouchHelper），
     *   无论注册顺序在前在后都收不到后续 MOVE（lastTouchY 停在起拖时的值）。
     */
    private var dragStartYInList = 0f

    /**
     * 起拖时被拖卡片在「列表坐标系」里的 top（= ItemTouchHelper#select 里的 mSelectedStartY）。
     * ItemTouchHelper 保证卡片视觉位置恒等于「起拖 top + 手指位移」，与列表是否自动滚动无关，
     * 因此「手指当前位置」必须由卡片视觉位移反推，不能直接用回调的 dY——dY 是
     * `mSelectedStartY + mDy - itemView.getTop()`，列表一滚动 getTop() 就变小，dY 会被
     * 灌入滚动量（实测：底部预留空间 + 展开 20 槽后滚动量可达 800+px，手指只拖 750px
     * 却算出 fingerY≈1800，直接被误判成「拖出列表」而取消，让位预览根本看不到）。
     */
    private var dragStartCardTop = 0f

    /** 最近一次算出的手指位置（列表坐标系），仅用于日志取证 */
    private var lastFingerY = 0f

    /** 越界判定只打一次日志，避免刷屏 */
    private var dragDebugOnce = false

    // ---------- 起拖「扩散到整卡」动画 ----------

    /**
     * 起拖扩散进度 0..1：0 = 波纹只有按压点那么大、卡片仍不透明；
     * 1 = 波纹已覆盖整卡、卡片 alpha 到 [DRAG_ALPHA]。见 [startDragReveal]。
     */
    private var dragRevealProgress = 0f

    /** 起拖扩散圆心：把手中心在**被拖卡片内**的相对坐标（= 用户实际按压点） */
    private var dragRevealCx = 0f
    private var dragRevealCy = 0f

    /** 起拖扩散动画句柄（复位 / 二次起拖时要 cancel，见 [resetDragVisuals]） */
    private var dragRevealAnim: ValueAnimator? = null

    /** 起拖扩散圆的画笔：渐变每帧按 [dragRevealProgress] 现建 RadialGradient */
    private val dragRevealPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ---------- 让位预览状态 / 插入指示框 / 双指控制 ----------

    /**
     * 让位预览状态（占用卡落点）：(起拖位, 落点位)。仅状态变化时驱动一次平移动画，
     * 避免每帧直接 set translationY 把动画打断、造成"反复抽动"。NO_POSITION = 当前无让位。
     */
    private var gapFromPos: Int = RecyclerView.NO_POSITION
    private var gapHoverPos: Int = RecyclerView.NO_POSITION

    /**
     * 让位预览的「撑开缓冲」锚点（= 起拖位，非槽位拖拽时为 NO_POSITION）。
     * 它必须并入状态 key：撑开量由它决定，锚点变了就得重算一次平移动画，
     * 否则换一行起拖时上一行的撑开偏移会残留。
     */
    private var gapPeekFrom: Int = RecyclerView.NO_POSITION

    /** 撑开缓冲量（px），onCreate 里按 [DRAG_PEEK_DP] 换算 */
    private var dragPeekPx = 0f

    /**
     * 插入位置指示框（缺口中间的蓝色小框，框内写落子后的槽位号）的动画状态。
     * 用户原话："显示变蓝的是上卡14或者是下卡15，用户会以为是覆盖…应该是中间变蓝，
     * 可以加个小框但是不要写空白框而是写数字，例如框可以显示为15"。
     * 位置/透明度逐帧向目标插值（缓动跟随），避免挪过去时一直闪烁。
     */
    private var insertBoxY = 0f
    private var insertBoxAlpha = 0f
    private var insertBoxTargetY = 0f
    private var insertBoxTargetAlpha = 0f
    private var insertBoxText = ""
    private val insertFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    // 成组拖拽角标（被拖卡片右上角"共 N 项"）：明示拖的是整组
    private val groupBadgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val groupBadgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val insertStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val insertTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 列表末尾额外可滚空间（px）：由 [BottomSpaceDecoration] 加在最后一项下方 */
    private var listBottomSpacePx = 0

    /** 双指控制：拖拽中第二根手指的 pointerId 与上次 y（另一只手指上下滑动滚动列表） */
    private var secondPointerId = -1
    private var secondPointerLastY = 0f

    /** 长按起拖等待期间的取消半径（px），onCreate 里按 [DRAG_HOLD_CANCEL_SLOP_DP] 换算 */
    private var pendingHoldSlop = 0f

    // ---------- 批量整理模式（本地暂存，点了保存才写设备） ----------

    /** 整理模式开关：列表常驻 20 槽位视图，勾选/拖动/删除全部只改本地 */
    private var organizeMode = false

    /** 进入整理模式时的设备频道快照（原数据留内存：取消丢弃、失败后可据此核对） */
    private var organizeOriginal: List<SealProtos.ChannelInfo> = emptyList()

    /** 整理模式被勾选的频道号（多选；频道号在整理期间不变，可作稳定身份） */
    private val organizeChecked = linkedSetOf<Int>()

    /** 整理模式被标记删除的频道号（划掉标记；保存时才真正 DELETE） */
    private val organizeDeleted = linkedSetOf<Int>()

    /** 本次整理是否发生了位置变化（用于「待提交 N 项改动」与保存按钮可用性） */
    private var organizeBaseline: List<Int> = emptyList()

    private var reorderTotal = 0
    private var reorderDone = 0

    /** 排序前 ch1/ch2 的原始激活频道（槽位下标 → 频道快照）。
     *  设备禁止删除激活中的频道（真机实测：DELETE ch1 当前频道 → 0x4E2B result=3 FORBIDE），
     *  排序前先把参与排序的激活槽切到不参与排序的频道，排序完成/中止后再按内容切回原频道的新 no */
    private val reorderOriginalActive = mutableListOf<Pair<Int, SealProtos.ChannelInfo>>()

    /** 排序前被临时切走的槽位下标集合（这些槽排序后必须切回原频道） */
    private val reorderPreSwitched = mutableSetOf<Int>()

    private var reorderStep: MoveStep? = null

    /** 当前步 DELETE 发送重试计数（写通道被占时短延重发，见 executeNextReorderStep） */
    private var reorderSendRetry = 0

    /** 激活串行下发轮次：过期的延时激活任务直接丢弃，避免误发旧排序的切换 */
    private var activationRound = 0

    /** 1=已发 DELETE 等 ACK；2=已发 INSERT 等 ACK */
    private var reorderPhase = 0

    private val reorderQueue = ArrayDeque<MoveStep>()
    private var reorderDialog: androidx.appcompat.app.AlertDialog? = null

    /** 单步确认超时兜底：设备未回 ACK 时停在当前步，提供「重试剩余 / 放弃」 */
    private val reorderTimeoutRunnable = Runnable { failReorderStep() }

    private val itemTouchCallback = object : ItemTouchHelper.Callback() {

        /** 仅自定义 tab 允许上下拖拽；排序中/有增删改在飞时禁止。
         *  空槽占位行不可被拖动（只能作为落点：canDropOver 默认 true） */
        override fun getMovementFlags(
            recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
        ): Int {
            if (viewHolder.itemViewType == ChannelAdapter.VIEW_TYPE_SLOT) return 0
            val flags = if (!showCustom || reorderActive || connector?.isChannelOpPending == true) 0
                else makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
            return flags
        }

        /**
         * 拖拽过程中不做任何换位：整张列表（含空槽占位行）原地不动，
         * 只有被拖起的卡片跟着手指走、目标行变蓝（见 [refreshHoverTarget]）。
         * 用户明确要求「空位在卡片移动到空槽位时不应该会移动」——旧实现在这里
         * adapter.moveItem(...) 做相邻换位，空槽会被顶走，落点判定也随之漂移。
         * 真正的换位推迟到松手（[finishSlotDrag] / [finishOrganizeDrag]）按蓝色落点一次性完成。
         */
        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean = false

        /**
         * 落点合法性：被拖行自己不算落点，其余行（频道行 / 空槽占位行）都可放置。
         * 蓝色高亮严格以本方法为准——用户以「变蓝」判定可以放这里（见 [refreshHoverTarget]）。
         */
        override fun canDropOver(
            recyclerView: RecyclerView,
            current: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            if (current === target) return false
            return super.canDropOver(recyclerView, current, target)
        }

        /**
         * (a) 拖动中的卡片半透明 + (b) 悬停落点变蓝。
         * 本方法在每次 ACTION_MOVE 后都会被调用（ItemTouchHelper#onTouchEvent 末尾
         * mRecyclerView.invalidate() → ItemDecoration#onDraw → onChildDraw），是刷新拖拽视觉最及时的点。
         */
        override fun onChildDraw(
            c: Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean
        ) {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            if (actionState != ItemTouchHelper.ACTION_STATE_DRAG) return
            if (!isCurrentlyActive) {
                // 松手后的回位动画（RecoverAnimation）同样走这里。
                // 有效落点：保持隐藏（resetDragVisuals 已置 0）——否则用户先看到卡片"飞回原位"，
                // 误以为拖拽失败（实际已成功，真正的换位在 finishSlotDrag 里按落点完成，
                // 列表重排后卡片在新位置重新出现）。只有无效落点（取消）才恢复不透明，
                // 让回位动画作为取消反馈正常播放。
                viewHolder.itemView.alpha = if (slotDragActive && dragHoverPos >= 0) 0f else 1f
                clearHoverTarget()
                resetInsertBox()
                return
            }
            if (dragActiveVh !== viewHolder) {
                dragActiveVh?.itemView?.alpha = 1f
                dragActiveVh = viewHolder
            }
            // 起拖扩散动画进行中：alpha 随波纹进度从 1 插值到 DRAG_ALPHA（"透明从按压点
            // 扩散到整卡"）；动画结束后 progress=1 → 恒等于 DRAG_ALPHA，与改动前一致。
            // 兜底分支：没走扩散动画（理论上不会，两处 startDrag 都调用了 startDragReveal）
            // 时直接半透明，退化成原行为，不会出现"拖起来完全不透明"。
            viewHolder.itemView.alpha = if (dragRevealAnim == null && dragRevealProgress == 0f) {
                DRAG_ALPHA
            } else {
                1f + (DRAG_ALPHA - 1f) * dragRevealProgress
            }
            // super 已把 translation 设成 dX/dY，据此刷新落点高亮（dY 同时用来算手指位置）
            refreshHoverTarget(recyclerView, viewHolder, dY)
            // 插入位置指示框画在 onDrawOver（被拖卡之上）：缺口中间的蓝色数字框
            drawInsertBox(c, recyclerView, viewHolder)
        }

        /**
         * 起拖扩散波纹画在被拖卡片之上（onDrawOver 层）：以把手中心（用户按压点）为圆心，
         * 半径随 [dragRevealProgress] 扩到卡片对角线，圆内主题青绿渐变（无边框描边）。
         * 只有 progress 在 0..1 之间（即起拖后 260ms 内）才画，画完自动消失。
         */
        override fun onChildDrawOver(
            c: Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder?,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean
        ) {
            super.onChildDrawOver(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            if (actionState != ItemTouchHelper.ACTION_STATE_DRAG) return
            drawDragReveal(c, viewHolder)
            updateGroupFollowerPositions(recyclerView, viewHolder)
            drawGroupBadge(c, viewHolder)
        }

        /**
         * 成组拖拽：让其余被勾选的**真实卡片**跟在被拖卡正下方一起移动。
         * 用户明确要求"直接使用现在的卡片、透明度要一样"——不画复制品：
         * 每帧把组员行的 translation 对到被拖卡下方第 i 格（列表顺序、跳过锚点），
         * alpha/translationZ 同步被拖卡（透明度一致、层级盖过普通行）。
         * 每帧在 onChildDraw 里调（MOVE 与自动滚动的每次 invalidate 都会走这里）；
         * 组员行已排除出让位预览（applyGapPreview/onBindViewHolder），两套位移不打架。
         * 组员行若被回收不在屏幕上（findViewHolder 为 null）就跳过，滚回来重绑后下一帧接上。
         */
        private fun updateGroupFollowerPositions(list: RecyclerView, dragged: RecyclerView.ViewHolder?) {
            if (adapter.dragGroupNos.size <= 1) return
            val anchorVh = dragged ?: return
            val anchor = anchorVh.itemView
            val aTop = anchor.top + anchor.translationY
            val dm = resources.displayMetrics
            // 屏幕下方放不下时压缩间距（最低 56px 保勾选块+频道名可读）
            val slack = dm.heightPixels - (aTop + anchor.height) - 120f * dm.density
            val m = adapter.dragGroupNos.size - 1
            val step = if (slack > 0) minOf(110f * dm.density, slack / m) else 56f * dm.density
            var i = 0
            for (pos in 0 until adapter.itemCount) {
                val ch = adapter.itemAt(pos) ?: continue
                if (ch.isSlotPlaceholder() || ch.no !in adapter.dragGroupNos) continue
                if (ch.no == adapter.dragAnchorNo) continue
                @Suppress("DEPRECATION")     // ViewHolder.adapterPosition（recyclerview 1.1.0）
                val holder = list.findViewHolderForAdapterPosition(pos)
                if (holder != null) {
                    val c = holder.itemView
                    c.translationX = anchor.translationX
                    c.translationY = aTop + anchor.height + step * i - c.top
                    c.translationZ = anchor.translationZ + 1f
                    c.alpha = anchor.alpha
                }
                i++
            }
        }

        /**
         * 成组拖拽角标：在被拖卡片右上角画「共 N 项」（深蓝底白字）。
         * 用户反馈"拖一组时只显示第一个勾选的，会让用户以为只选了一个"——
         * 半透明的组员行 + 这个角标双保险。数据源 [ChannelAdapter.dragGroupNos]，
         * 只在整理模式成组拖拽时画。
         */
        private fun drawGroupBadge(c: Canvas, vh: RecyclerView.ViewHolder?) {
            if (!organizeMode || !slotDragActive) return
            val n = adapter.dragGroupNos.size
            if (n <= 1) return
            val v = vh?.itemView ?: return
            val dm = resources.displayMetrics
            val text = "共 $n 项"
            val padH = 8f * dm.density
            val padV = 4f * dm.density
            val inset = 12f * dm.density
            val tw = groupBadgeTextPaint.measureText(text)
            val th = groupBadgeTextPaint.descent() - groupBadgeTextPaint.ascent()
            val badgeH = th + padV * 2
            val right = v.left + v.translationX + v.width - inset
            // 整枚角标必须完全落在卡片内（top = 卡顶+inset）：若按"bottom = 卡顶+inset"
            // 写，角标高 > inset 时会伸出卡片上沿、压到页签栏（真机实测）
            val top = v.top + v.translationY + inset
            val bottom = top + badgeH
            val left = right - tw - padH * 2
            val radius = 10f * dm.density
            groupBadgeBgPaint.alpha = 235
            c.drawRoundRect(left, top, right, bottom, radius, radius, groupBadgeBgPaint)
            groupBadgeTextPaint.alpha = 255
            c.drawText(text, (left + right) / 2f, bottom - padV - groupBadgeTextPaint.descent(),
                groupBadgeTextPaint)
        }

        /**
         * 复位总闸：ACTION_STATE_IDLE 一定会被调用（ItemTouchHelper#select 末尾无条件回调
         * onSelectedChanged，包括「拖完走 RecoverAnimation」那条分支——那条分支的 clearView
         * 要等 200ms 回位动画结束才到）。因此半透明/蓝色高亮/底部安全空间统一在这里复位，
         * 只依赖 clearView 会在回收路径上残留。
         */
        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) resetDragVisuals()
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        /** 拖拽由按下把手发起；行本身长按保留 [删除/编辑/取消] 选项菜单 */
        override fun isLongPressDragEnabled() = false

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            // 把手手势结束的可靠复位点：startDrag 后 RecyclerView 的 OnItemTouchListener 会
            // 拦截后续事件（dispatchToOnItemTouchListeners 不再派发给子视图），把手里
            // ACTION_UP/CANCEL 永远收不到，只能在这里解除行点击/长按屏蔽
            // （读 recyclerview-1.1.0 源码确认；不复位则一次把手触摸后整列表交互失效）
            adapter.endHandleGesture()
            if (!showCustom) return
            // clearView 不只在正常松手时被调用：拖动中的行被回收/重绑时，
            // ItemTouchHelper#onChildViewDetachedFromWindow → endRecoverAnimation →
            // RecoverAnimation.cancel → onAnimationEnd 也会走到这里，此时正处于
            // RecyclerView 的布局过程（onLayout → dispatchLayoutStep2 → onLayoutChildren →
            // detachAndScrapAttachedViews）。在这里做 notifyDataSetChanged 会命中
            // RecyclerView#assertNotInLayoutOrScroll 抛 IllegalStateException（真机实测崩溃，
            // a2.log 05:23:51）。因此折叠列表 / 执行迁移一律推迟到本次布局结束后的下一帧。
            recyclerView.post {
                finishSlotDrag()
                // 有效落点时被拖卡片在松手瞬间被隐藏（alpha 0）以压掉回位动画；
                // finish 内的 submit / notifyItemRangeChanged 会重绑复位（onBindViewHolder 里重置），
                // 这里再兜底显式恢复一次，防"原地落点"等不触发重绑的边界情况下卡片不可见
                viewHolder.itemView.alpha = 1f
            }
        }
    }

    // ---------- 拖拽视觉：半透明 / 悬停落点变蓝 / 底部安全空间 ----------

    /**
     * (b) 悬停落点高亮：只有满足 [itemTouchCallback.canDropOver] 的行才变蓝，
     * 手指移出或命中非法行时立刻清除上一个高亮（不残留）。
     * 高亮直接改视图状态，不走 notify——拖拽期间 notifyDataSetChanged 会命中
     * RecyclerView#assertNotInLayoutOrScroll 抛 IllegalStateException（真机实测崩溃，a2.log 05:23:51）。
     */
    private fun refreshHoverTarget(
        list: RecyclerView,
        dragged: RecyclerView.ViewHolder,
        dY: Float = 0f
    ) {
        // 落点 = 与被拖卡片垂直重叠最多的那一行。
        // 不能用「卡片中心点命中哪一行」：频道卡片 375px 高，中心永远落在自己身上，
        // 那样永远不会点亮别的行（真机推导确认）。重叠最多的相邻行才是用户眼中的目标槽位。
        // 手指当前位置（列表坐标系）= 起拖手指位置 + 卡片视觉位移。
        // 卡片视觉位移 = 当前视觉 top - 起拖视觉 top（见 [dragStartCardTop]）——
        // 这样算出来的手指位置不受列表自动滚动影响。
        val v = dragged.itemView
        val top = v.top + v.translationY
        val bottom = top + v.height
        val fingerY = dragStartYInList + (top - dragStartCardTop)
        // 拖出列表（上方或下方）＝ 放弃本次拖拽：清掉落点与蓝色高亮。
        // 不判这一条的话，ItemTouchHelper 的边缘自动滚动会一直把列表滚下去，dragHoverPos
        // 几乎不可能落到 NO_POSITION——用户以为「拖出去 = 取消」，松手却照样改写设备
        // （QA 实测：往上方拖出去一次改写了 5 个频道位置、6 步写入全部落盘）。
        //
        // 判据以「手指是否离开列表」为准，不看书卡片是否还在可视区：拖到边缘时列表自动
        // 滚动会把卡片带着走、与手指脱节，只看卡片会导致「甩到底立即松手仍写入、停留几
        // 秒才取消」这种和停留时长相关的怪行为（QA 实测）。手指位置不受滚动影响。
        // 手指当前位置（列表坐标系）= 起拖手指位置 + 卡片视觉位移（见 [dragStartCardTop]）
        lastFingerY = fingerY
        if (dragDebugOnce) {
            dragDebugOnce = false
            log("拖拽调试: fingerY=$fingerY listH=${list.height} " +
                "card=[$top,$bottom] childCount=${list.childCount}")
        }
        val cancelMargin = DRAG_CANCEL_MARGIN_DP * resources.displayMetrics.density
        val fingerOut = fingerY < -cancelMargin || fingerY > list.height + cancelMargin
        // 卡片整体移出列表也算（兜底：手指坐标缺失/异常时仍能取消）
        val cardOut = minOf(bottom, list.height.toFloat()) - maxOf(top, 0f) <= 0f
        if (fingerOut || cardOut) {
            if (dragHoverVh != null) clearHoverTarget()
            dragHoverPos = RecyclerView.NO_POSITION
            clearGapPreview(list, dragged)
            if (dragDebugOnce) {
                dragDebugOnce = false
                log("拖拽越界: fingerY=$fingerY listH=${list.height} " +
                    "card=[$top,$bottom] fingerOut=$fingerOut cardOut=$cardOut")
            }
            return
        }
        // 命中规则：取「卡片中心线所在的行」，且必须用各行的**原始布局位置**（child.top）判，
        // 不能用 top + translationY —— 候选行自己会被让位动画平移，把平移后的位置拿回来判
        // 交会形成正反馈：行被推下去 → 判到更下面一行 → 又把它推下去 …… 一路滑到列表末尾
        // （真机实测：拖到 01/02 之间松手却落到第 6 行）。用原始位置做命中后，让位动画只是
        // 渲染结果，不再影响判定，目标稳定。
        //
        // 被拖的那一行也参与命中：卡片还在自己原来的格子里时 should hover = 自身 → 换位为空操作。
        val centerY = (top + bottom) / 2f
        // canDropOver 对「目标=自己」返回 false，自己的格子要单独先判：卡片还没离开原格时
        // 落点就是自身（换位为空操作），否则会掉进下面的"最近行"兜底、误判到相邻行。
        val selfTop = v.top.toFloat()
        val selfBottom = selfTop + v.height
        var best: RecyclerView.ViewHolder? = null
        if (centerY >= selfTop && centerY < selfBottom) {
            best = dragged
        } else {
            for (i in 0 until list.childCount) {
                val child = list.getChildAt(i)
                val holder = list.getChildViewHolder(child) ?: continue
                if (!itemTouchCallback.canDropOver(list, dragged, holder)) continue
                val cTop = child.top.toFloat()
                val cBottom = cTop + child.height
                if (centerY >= cTop && centerY < cBottom) {
                    best = holder
                    break
                }
            }
        }
        // 兜底：中心落在可见行之外（列表顶部/底部留白、底部预留区）时取距离最近的一行。
        // 底部常驻内边距让最后一槽停在按钮上方的高位后，"一路拖到最底部"会越过末行，
        // 有此兜底即可一直咬住末行（用户实测"非常难用"）。
        if (best == null) {
            var bestDist = Float.MAX_VALUE
            for (i in 0 until list.childCount) {
                val child = list.getChildAt(i)
                val holder = list.getChildViewHolder(child) ?: continue
                if (!itemTouchCallback.canDropOver(list, dragged, holder)) continue
                val cTop = child.top.toFloat()
                val cBottom = cTop + child.height
                val dist = if (centerY < cTop) cTop - centerY else centerY - cBottom
                if (dist < bestDist) {
                    bestDist = dist
                    best = holder
                }
            }
        }
        val next = best
        @Suppress("DEPRECATION")     // ViewHolder.adapterPosition（recyclerview 1.1.0）
        val nextPos = next?.adapterPosition ?: RecyclerView.NO_POSITION
        applyGapPreview(list, dragged, nextPos)
        if (next === dragHoverVh) return
        log("hover->$nextPos centerY=$centerY self=[$selfTop,$selfBottom] fingerY=$fingerY")
        dragHoverPos = nextPos
        applyHover(dragHoverVh, false)
        dragHoverVh = next
        // 占用卡落点不再整行变蓝：用户会以为"覆盖"，且分不清落子后自己会变 15 还是 13
        // （用户实测）。落点改由缺口处的插入指示框（蓝色数字框）表达；
        // 空槽落点维持占位框变蓝（框内本就有槽位号，语义明确）。
        if (next != null && next.itemViewType == ChannelAdapter.VIEW_TYPE_SLOT) {
            applyHover(next, true)
        }
    }

    /**
     * 占用卡落点让位预览：把 (fromPos, hoverPos]（向下拖）或 [hoverPos, fromPos)（向上拖）
     * 范围的行整体平移一个「被拖卡片高度」，腾出落点缺口；空槽落点不让位（只变蓝）。
     * 被拖行自身不碰——它的 translationY 由 ItemTouchHelper 管理。
     *
     * 动画策略：仅当让位状态（起拖位/落点位）变化时驱动一次 ViewPropertyAnimator 过渡，
     * 之后每帧进来直接返回——不会把进行中的动画打断，避免"反复抽动"（用户实测）。
     * 自动滚动导致区间内行被回收重绑时，由 onBindViewHolder 读 [ChannelAdapter.gapPreview]
     * 直接补位移（无动画），保证缺口连续。
     *
     * 用户原话："空卡槽位置变蓝时不要移动，要方便用户放置；其他卡片例如 2-3 是有频率的，
     * 我想把 5 挪到 2-3 中间，卡片应该让开位置让我放进去"。
     */
    private fun applyGapPreview(
        list: RecyclerView,
        dragged: RecyclerView.ViewHolder,
        hoverPos: Int
    ) {
        @Suppress("DEPRECATION")     // ViewHolder.adapterPosition（recyclerview 1.1.0）
        val fromPos = dragged.adapterPosition
        val hoverItem = if (hoverPos >= 0) adapter.itemAt(hoverPos) else null
        val occupied = hoverPos >= 0 && hoverPos != fromPos &&
            hoverItem != null && !hoverItem.isSlotPlaceholder()
        val newFrom = if (occupied) fromPos else RecyclerView.NO_POSITION
        val newHover = if (occupied) hoverPos else RecyclerView.NO_POSITION
        // 撑开缓冲的锚点：只要槽位拖拽进行中就生效，**哪怕还没形成落点缺口**——
        // 用户原话"拖 15 时 14 上移、16 下移"说的是"卡片一被拎起来两边就该让一让"，
        // 挂在 occupied 上的话（缺口形成后），起拖位顺方向那一侧的行必然已经落在让位区间里
        // （往下拖时 fromPos+1 恒在区间内），peek 会被区间覆盖、整条效果看不见。
        val newPeekFrom = if (slotDragActive) fromPos else RecyclerView.NO_POSITION
        // 状态没变：平移与序号预览都已生效（含 onBindViewHolder 给新绑定行补的），直接返回
        if (newFrom == gapFromPos && newHover == gapHoverPos && newPeekFrom == gapPeekFrom) return
        gapFromPos = newFrom
        gapHoverPos = newHover
        gapPeekFrom = newPeekFrom
        val draggedView = dragged.itemView
        val shift = draggedView.height.toFloat()
        // 无缺口时也要构造（hoverPos = NO_POSITION）：onBindViewHolder 靠它给回收重绑的行
        // 补上撑开偏移，否则滚动时"两边让开"会断
        adapter.gapPreview = if (newPeekFrom != RecyclerView.NO_POSITION) {
            GapPreview(newFrom, newHover, shift, newPeekFrom, dragPeekPx)
        } else {
            null
        }
        val down = newHover > newFrom
        // 整理模式序号实时预览：被拖行显示落点号、让位行显示目标位号、其余行复位
        // （用户："批量模式下这种移动序号应该显示变化，5 挪到 2-3 中间，5 应变 3、原 3 变 4"）
        if (adapter.numberByPosition) {
            if (occupied) setRowNo(draggedView, dragged, newHover) else restoreRowNos(list)
        }
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            if (child === draggedView) continue
            val holder = list.getChildViewHolder(child) ?: continue
            @Suppress("DEPRECATION")     // ViewHolder.adapterPosition（recyclerview 1.1.0）
            val pos = holder.adapterPosition
            if (pos == RecyclerView.NO_POSITION) continue
            // 成组拖拽的组员行不参与让位/序号预览：它们已被"提走"、以真实卡片跟在被拖卡
            // 下方一起移动（updateGroupFollowerPositions 每帧接管其 translation/alpha）
            val chAt = adapter.itemAt(pos)
            if (chAt != null && !chAt.isSlotPlaceholder() &&
                chAt.no in adapter.dragGroupNos && chAt.no != adapter.dragAnchorNo
            ) continue
            val inRange = occupied &&
                (if (down) pos in (newFrom + 1)..newHover else pos in newHover until newFrom)
            if (adapter.numberByPosition) {
                setRowNo(child, holder, if (inRange) (if (down) pos - 1 else pos + 1) else pos)
            }
            // 目标偏移统一由 GapPreview#shiftFor 算（与 onBindViewHolder 补位移同一份公式）：
            // 区间内整行让位 + 起拖位上下相邻行的撑开缓冲。
            val target = adapter.gapPreview?.shiftFor(pos) ?: 0f
            if (child.translationY != target) {
                child.animate().cancel()
                child.animate().translationY(target)
                    .setDuration(GAP_ANIM_MS)
                    // 弹性撑开：略微过冲再回弹，卡片"被挤开"的手感更明显（改动 3）
                    .setInterpolator(OvershootInterpolator(0.6f))
                    .start()
            }
        }
    }

    /**
     * 把某一行的显示序号直接改成目标位号（拖拽期间 notifyDataSetChanged 会崩，只能直接改视图；
     * 格式与 onBindViewHolder 保持一致：频道行 "%02d  名称"、空槽行纯数字）。
     */
    private fun setRowNo(child: View, holder: RecyclerView.ViewHolder, targetPos: Int) {
        val h = holder as? ChannelAdapter.VH ?: return
        val no = targetPos + 1
        if (h.itemViewType == ChannelAdapter.VIEW_TYPE_SLOT) {
            h.b.slotNo.text = no.toString()
        } else {
            @Suppress("DEPRECATION")     // ViewHolder.adapterPosition（recyclerview 1.1.0）
            val ch = adapter.itemAt(h.adapterPosition) ?: return
            h.b.chTitle.text = "%02d  %s".format(no, ch.name.ifEmpty { "CH $no" })
        }
    }

    /** 复位所有可见行的显示序号为各自当前位号（整理模式序号预览的复位路径） */
    private fun restoreRowNos(list: RecyclerView) {
        if (!adapter.numberByPosition) return
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            val holder = list.getChildViewHolder(child) ?: continue
            @Suppress("DEPRECATION")     // ViewHolder.adapterPosition（recyclerview 1.1.0）
            val pos = holder.adapterPosition
            if (pos == RecyclerView.NO_POSITION) continue
            setRowNo(child, holder, pos)
        }
    }

    /** 清除让位平移（动画归位，不再硬设造成抽动）+ 复位预览序号 + 清空让位状态 */
    private fun clearGapPreview(list: RecyclerView, dragged: RecyclerView.ViewHolder?) {
        if (gapFromPos == RecyclerView.NO_POSITION && gapHoverPos == RecyclerView.NO_POSITION &&
            gapPeekFrom == RecyclerView.NO_POSITION
        ) {
            adapter.gapPreview = null
            return
        }
        gapFromPos = RecyclerView.NO_POSITION
        gapHoverPos = RecyclerView.NO_POSITION
        gapPeekFrom = RecyclerView.NO_POSITION
        adapter.gapPreview = null
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            if (child === dragged?.itemView) continue
            if (child.translationY != 0f) {
                child.animate().cancel()
                child.animate().translationY(0f)
                    .setDuration(GAP_ANIM_MS)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
        }
        restoreRowNos(list)
    }

    /**
     * 绘制插入位置指示框：落在缺口中间的蓝色小框，框内写落子后的槽位号。
     * 用户原话："显示变蓝的是上卡14或者是下卡15，用户会以为是覆盖…应该是中间变蓝，
     * 可以加个小框但是不要写空白框而是写数字，例如框可以显示为15"。
     * 位置 = 落点行的原始布局 top（该行已被让位平移推开，其原位置即缺口）；
     * 画在 onDrawOver（被拖卡之上），位置与透明度逐帧向目标插值——缓动跟随，
     * 避免挪过去的时候一直闪烁。
     */
    private fun drawInsertBox(c: Canvas, list: RecyclerView, dragged: RecyclerView.ViewHolder) {
        val hoverPos = dragHoverPos
        val hoverItem = if (hoverPos >= 0) adapter.itemAt(hoverPos) else null
        val show = hoverPos >= 0 && hoverItem != null && !hoverItem.isSlotPlaceholder()
        if (show) {
            val hoverChild = list.findViewHolderForAdapterPosition(hoverPos)?.itemView
            if (hoverChild != null) {
                insertBoxTargetY = hoverChild.top.toFloat()
                insertBoxTargetAlpha = 1f
                // 两位补零，和列表行号格式一致（"02" 而不是 "2"）
                insertBoxText = "%02d".format(hoverPos + 1)
            }
        } else {
            insertBoxTargetAlpha = 0f
        }
        // 缓动跟随：位置与透明度都向目标插值，平滑过渡不闪烁
        insertBoxY += (insertBoxTargetY - insertBoxY) * INSERT_BOX_FOLLOW
        insertBoxAlpha += (insertBoxTargetAlpha - insertBoxAlpha) * INSERT_BOX_FOLLOW
        if (insertBoxAlpha < 0.02f) {
            insertBoxAlpha = 0f
            return
        }
        val dm = resources.displayMetrics
        val boxH = 56f * dm.density
        val margin = 16f * dm.density
        val radius = 12f * dm.density
        val left = margin
        val right = list.width - margin
        // 缺口高度 = 被拖卡片高度（让位平移量），框在缺口里垂直居中
        val gapH = dragged.itemView.height.toFloat()
        val cy = insertBoxY + gapH / 2f
        val top = cy - boxH / 2f
        val bottom = cy + boxH / 2f
        val alpha = (insertBoxAlpha * 255).toInt()
        insertFillPaint.alpha = alpha
        c.drawRoundRect(left, top, right, bottom, radius, radius, insertFillPaint)
        insertStrokePaint.alpha = alpha
        c.drawRoundRect(left, top, right, bottom, radius, radius, insertStrokePaint)
        insertTextPaint.alpha = alpha
        val textY = cy - (insertTextPaint.descent() + insertTextPaint.ascent()) / 2f
        c.drawText(insertBoxText, list.width / 2f, textY, insertTextPaint)
    }

    /** 硬复位插入指示框（拖拽结束/取消必须调用，否则最后一帧的 alpha 会冻住画面上） */
    private fun resetInsertBox() {
        insertBoxAlpha = 0f
        insertBoxTargetAlpha = 0f
        insertBoxY = insertBoxTargetY
        insertBoxText = ""
    }

    private fun clearHoverTarget() {
        applyHover(dragHoverVh, false)
        dragHoverVh = null
    }

    /** 渲染/擦除一行的落点高亮（擦除时回到 onBindViewHolder 的基准状态） */
    @Suppress("DEPRECATION")     // ViewHolder.adapterPosition（recyclerview 1.1.0）
    private fun applyHover(vh: RecyclerView.ViewHolder?, on: Boolean) {
        val holder = vh as? ChannelAdapter.VH ?: return
        if (holder.itemViewType == ChannelAdapter.VIEW_TYPE_SLOT) {
            holder.b.placeholderBox.setBackgroundResource(
                if (on) R.drawable.bg_slot_drop_target else R.drawable.bg_slot_placeholder
            )
            holder.b.slotNo.setTextColor(
                if (on) 0xFF28AA9A.toInt() else 0xFF90A4AE.toInt()
            )
        } else {
            holder.itemView.setBackgroundColor(
                if (on) 0xFF3328AA9A.toInt() else adapter.rowBaseColor(holder.adapterPosition)
            )
        }
    }

    /**
     * 拖拽期间淡出底部浮层（fabRow/organizeBar 画在 RecyclerView 之上，会盖住拖到底部的卡片）。
     * 用户实测"只是加了个图层，往下拖会被这个图层盖住"——底部预留空间 + 浮层双重遮挡，
     * 现在预留空间已去除，浮层在拖拽期间淡出即可保证最后一槽完整可见、可放置。
     */
    private fun setBottomBarForDrag(dragging: Boolean) {
        val bar = if (organizeMode) binding.organizeBar else binding.fabRow
        bar.animate().cancel()
        bar.animate().alpha(if (dragging) 0f else 1f).setDuration(150).start()
    }

    /**
     * 起拖时「从按压点扩散到整卡」的透明动画（改动 2）：
     * 以把手中心（用户实际按压点）为圆心，波纹在 [DRAG_REVEAL_MS] 内扩散覆盖整张卡片，
     * 同时卡片 alpha 由 1 渐变到 [DRAG_ALPHA]——视觉上就是"卡片从手指按住的地方被拎起来"。
     *
     * 必须在 **itemTouchHelper.startDrag 之前** 调用（两处 startDrag 都调了）：
     * startDrag 后 ItemTouchHelper 接管事件流，此时再取坐标拿到的仍是同一帧，但卡片已被
     * 抬起（translation 变化），圆心会偏。
     */
    private fun startDragReveal(vh: RecyclerView.ViewHolder) {
        dragRevealAnim?.cancel()
        val card = vh.itemView
        val handle = card.findViewById<View>(R.id.chDrag)
        // 按压点 = 把手中心在「卡片坐标系」里的坐标。
        // 算法：分别对 handle 与 card 调 getLocationInWindow()（都是**窗口**坐标，同一坐标系）
        // 再相减 → 得到把手在卡片内的相对位置，加上把手自身半宽/半高即中心点。
        // 为什么不用 rawY / 列表坐标：列表滚动、卡片 translation、状态栏偏移都会混进来
        // （dragStartYInList 那套要额外减列表 top 才对）；窗口坐标相减与这些全都无关，
        // 是唯一不需要知道"卡片现在在哪"的算法。
        val hLoc = IntArray(2)
        val cLoc = IntArray(2)
        handle.getLocationInWindow(hLoc)
        card.getLocationInWindow(cLoc)
        val w = card.width.toFloat()
        val h = card.height.toFloat()
        // 兜底：极少数情况（起拖那一帧卡片尚未完成布局）会算出 0 或越界值，夹回卡片中心，
        // 波纹顶多从正中扩散，不会画到卡片外面去
        val rawCx = hLoc[0] - cLoc[0] + handle.width / 2f
        val rawCy = hLoc[1] - cLoc[1] + handle.height / 2f
        dragRevealCx = if (w > 0f) rawCx.coerceIn(0f, w) else 0f
        dragRevealCy = if (h > 0f) rawCy.coerceIn(0f, h) else 0f
        dragRevealProgress = 0f
        card.alpha = 1f
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = DRAG_REVEAL_MS
        anim.interpolator = android.view.animation.DecelerateInterpolator()
        anim.addUpdateListener {
            dragRevealProgress = it.animatedValue as Float
            card.alpha = 1f + (DRAG_ALPHA - 1f) * dragRevealProgress
            // 波纹画在 RecyclerView 的 onDrawOver 层（不是卡片自己），必须整表重绘才会刷新；
            // 卡片 alpha 的变化本身不需要 invalidate，这里是为了驱动波纹的每一帧。
            binding.channelList.invalidate()
        }
        anim.start()
        dragRevealAnim = anim
    }

    /**
     * 画起拖扩散波纹（在 RecyclerView 的 onDrawOver 层、被拖卡片之上）：
     * 圆内主题青绿渐变，随 [dragRevealProgress] 从按压点扩到整卡，扩满后淡出消失。
     * 圆心跟着卡片当前视觉位置走——起拖瞬间用户就已经在挪手指，波纹不能留在原地。
     */
    private fun drawDragReveal(c: Canvas, vh: RecyclerView.ViewHolder?) {
        val p = dragRevealProgress
        if (p <= 0f || p >= 1f) return
        val v = vh?.itemView ?: return
        val w = v.width.toFloat()
        val h = v.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val left = v.left + v.translationX
        val top = v.top + v.translationY
        val cx = left + dragRevealCx
        val cy = top + dragRevealCy
        // 覆盖整卡所需的半径上限：卡片对角线 × 1.05（留一点余量压掉圆角处的漏白）
        val maxR = Math.hypot(w.toDouble(), h.toDouble()).toFloat() * 1.05f
        val r = maxR * p
        val fade = 1f - p
        val save = c.save()
        c.clipRect(left, top, left + w, top + h)
        // 渐变（主题色）：圆心处最浓、边缘透明，看起来像从按压点"漾"开
        dragRevealPaint.shader = RadialGradient(
            cx, cy, maxOf(r, 1f),
            intArrayOf(0xFF28AA9A.toInt(), 0x0028AA9A),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        dragRevealPaint.style = Paint.Style.FILL
        dragRevealPaint.alpha = (110f * fade).toInt()
        c.drawCircle(cx, cy, r, dragRevealPaint)
        c.restoreToCount(save)
    }

    /** 拖拽视觉复位：半透明恢复/隐藏、蓝色高亮清除、让位平移归位、插入框消失、底部浮层恢复 */
    private fun resetDragVisuals() {
        // 起拖扩散动画必须显式 cancel：动画在 260ms 内每帧改卡片 alpha，
        // 若拖拽在此期间结束（快速甩一下就松手），动画会继续把已复位卡片的 alpha 改回去
        dragRevealAnim?.cancel()
        dragRevealAnim = null
        dragRevealProgress = 0f
        clearHoverTarget()
        clearGapPreview(binding.channelList, dragActiveVh)
        // 有效落点：立刻隐藏被拖卡片，压掉 ItemTouchHelper 的"飞回原位"回位动画
        // （用户实测误以为失败；换位在 finishSlotDrag/finishOrganizeDrag 里完成）。
        // 无效落点：恢复不透明，回位动画即取消反馈（用户明确：只有无效操作才回原位）。
        val validDrop = slotDragActive && dragHoverPos >= 0
        dragActiveVh?.itemView?.alpha = if (validDrop) 0f else 1f
        dragActiveVh = null
        // 成组拖拽组员复位：translation/alpha 全归零（它们是被拖着走的真实卡片）；
        // 行内容 alpha 由随后的整表重绑兜底。不 notify 的原因见 clearView 注释。
        val oldGroup = adapter.dragGroupNos
        if (oldGroup.isNotEmpty()) {
            adapter.dragGroupNos = emptySet()
            adapter.dragAnchorNo = -1
            val list = binding.channelList
            for (i in 0 until list.childCount) {
                val child = list.getChildAt(i) ?: continue
                val pos = list.getChildAdapterPosition(child)
                if (pos == RecyclerView.NO_POSITION) continue
                val ch = adapter.itemAt(pos) ?: continue
                if (ch.no in oldGroup) {
                    child.animate().cancel()
                    child.translationX = 0f
                    child.translationY = 0f
                    child.translationZ = 0f
                    child.alpha = 1f
                }
            }
        }
        resetInsertBox()
        secondPointerId = -1
        setBottomBarForDrag(false)
        binding.channelSwipe.isEnabled = true
    }

    /**
     * 列表底部预留空间 = 当前底部操作栏高度 + 其 bottomMargin + 16dp 间隙 + 一整行卡片高度。
     * 让最后一个频道/空槽滚到底时完整露在「整理 / +」浮层按钮上方一行的位置——用户实测
     * "底部空间不够盖住了最后一个频道"，且要求"往下划时空槽在稍微上方一点的位置（约屏幕 1/3）"。
     *
     * 实现方式必须是 **ItemDecoration 给最后一项加的底部偏移**，不能用 RecyclerView 的
     * paddingBottom：RecyclerView 的 padding 会直接抬高 ItemTouchHelper 的自动滚动触发线
     * ——它判的是
     * `mSelectedStartY + mDy + itemHeight + decoration.bottom > getHeight() - getPaddingBottom()`
     * （读 recyclerview-1.1.0 ItemTouchHelper#scrollIfNecessary 源码）。我加的 ~500px 内边距
     * 把触发线抬到屏幕中部，卡片一进下半屏 `bottomDiff` 就一直 > 0，`scrollY = bottomDiff`
     * 每帧照加，列表一路自动滚到末尾：真机实测"想放到 01/02 之间，松手却落到第 18 行"。
     * decoration 偏移不进这条判据（只有被拖动项自己的 decoration 会计入，而那是拖它本身
     * 时的向上分支），是真正"列表滚到底后多出来的行程"，语义上也正好是用户要的底部空间。
     */
    private fun refreshListBottomPadding() {
        val list = binding.channelList
        val bar = if (organizeMode) binding.organizeBar else binding.fabRow
        val barH = if (bar.visibility == View.VISIBLE) maxOf(bar.height, bar.measuredHeight) else 0
        val mlp = bar.layoutParams as? ViewGroup.MarginLayoutParams
        val margin = mlp?.bottomMargin ?: 0
        val gap = (16 * resources.displayMetrics.density).toInt()
        // 额外预留一行高度：滚到底时最后一槽停在按钮上方约一行处，拖拽时有把空槽往上带的行程
        val rowH = list.getChildAt(0)?.height ?: 0
        val target = barH + margin + gap + rowH
        if (listBottomSpacePx != target) {
            listBottomSpacePx = target
            list.invalidateItemDecorations()
        }
    }

    /**
     * 只在最后一项下方加可滚动空间：滚到底时末行抬起 [listBottomSpacePx]，
     * 恰好落在底部浮层按钮上方（见 [refreshListBottomPadding] 的成因说明）。
     */
    private inner class BottomSpaceDecoration : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State
        ) {
            val pos = parent.getChildAdapterPosition(view)
            val last = parent.adapter?.itemCount?.minus(1) ?: return
            if (pos == last && listBottomSpacePx > 0) outRect.bottom = listBottomSpacePx
        }
    }

    private val adapter = ChannelAdapter(
        onClick = { ch -> if (!organizeMode) activateCustom(ch) },
        onLongClick = { ch, pos -> if (!organizeMode) showChannelOptions(ch, pos) },
        onStartDrag = { vh -> beginSlotDrag(vh) },
        onCheck = { no, checked, pos -> organizeOnCheck(no, checked, pos) }
    )

    private lateinit var itemTouchHelper: ItemTouchHelper

    private val publicAdapter = PresetAdapter { activatePreset(it) }
    private val remoteAdapter = PresetAdapter { activatePreset(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelSettingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.channelList.layoutManager = LinearLayoutManager(this)
        binding.channelList.adapter = adapter
        itemTouchHelper = ItemTouchHelper(itemTouchCallback)
        itemTouchHelper.attachToRecyclerView(binding.channelList)
        // 插入位置指示框（缺口中间的蓝色数字框）画笔
        val dm = resources.displayMetrics
        insertFillPaint.color = 0xFF3328AA9A.toInt()
        insertStrokePaint.color = 0xFF28AA9A.toInt()
        insertStrokePaint.strokeWidth = 2f * dm.density
        insertStrokePaint.style = Paint.Style.STROKE
        insertTextPaint.color = 0xFF28AA9A.toInt()
        insertTextPaint.textAlign = Paint.Align.CENTER
        insertTextPaint.isFakeBoldText = true
        insertTextPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20f, dm)
        // 成组拖拽角标：深蓝底白字，与插入框同一色系
        groupBadgeBgPaint.color = 0xFF28AA9A.toInt()
        groupBadgeTextPaint.color = 0xFFFFFFFF.toInt()
        groupBadgeTextPaint.textAlign = Paint.Align.CENTER
        groupBadgeTextPaint.isFakeBoldText = true
        groupBadgeTextPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, dm)
        pendingHoldSlop = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, DRAG_HOLD_CANCEL_SLOP_DP, dm)
        // 让位缓冲：起拖位上下相邻行各撑开的距离（改动 3）
        dragPeekPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, DRAG_PEEK_DP, dm)
        // 底部空间用 ItemDecoration（不用 RecyclerView padding，见 refreshListBottomPadding 注释）
        binding.channelList.addItemDecoration(BottomSpaceDecoration())
        // 列表底部常驻留出底部操作栏高度：最后一个频道/空槽滚到底时露在「整理/+」按钮上方
        // （用户实测"底部空间不够盖住了最后一个频道"）。等首帧布局后按钮才有高度。
        binding.channelList.post { refreshListBottomPadding() }

        // 预设两组 5 列网格（设备端 GridLayoutManager spanCount=5）
        binding.presetPublicGrid.layoutManager = GridLayoutManager(this, 5)
        binding.presetPublicGrid.adapter = publicAdapter
        binding.presetRemoteGrid.layoutManager = GridLayoutManager(this, 5)
        binding.presetRemoteGrid.adapter = remoteAdapter
        publicAdapter.submit(buildPresetChannels(SealProtos.ChType.PUBLIC))
        remoteAdapter.submit(buildPresetChannels(SealProtos.ChType.REMOTE))

        binding.btnBack.setOnClickListener {
            // 整理模式是纯本地暂存：退出前确认一次，避免误丢未提交的整理
            if (organizeMode) confirmDiscardOrganize { finish() } else finish()
        }
        binding.btnEditCurrent.setOnClickListener {
            // 预设频道也可进编辑页：名称/频道号/频率只读，仅亚音可改（见 ChannelEditActivity 的
            // isPreset 只读化）；这里不再拦截
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
        binding.tabPreset.setOnClickListener {
            if (organizeMode) confirmDiscardOrganize { selectTab(false) } else selectTab(false)
        }

        // 批量整理：进入 / 多选删除标记 / 取消丢弃 / 保存提交（全部只改本地，保存才写设备）
        binding.btnOrganize.setOnClickListener { enterOrganizeMode() }
        binding.btnOrganizeDelete.setOnClickListener { organizeToggleDeleteMark() }
        binding.btnOrganizeCancel.setOnClickListener {
            if (!organizeMode) return@setOnClickListener
            confirmDiscardOrganize { }
        }
        binding.btnOrganizeSave.setOnClickListener { commitOrganize() }

        // 头部回显本槽位当前信道（主页已读到的 ch1/ch2）
        currentCh = connector?.channels?.value?.getOrNull(slotIndex)
        currentCh?.let { showCurrent(it) }
        adapter.current = currentCh
        refreshPresetHighlight()

        observeData()
        refreshList()

        setupPullToRefresh()

        // 进入页面主动查询一次设备上的自定义信道
        if (connector?.state?.value == BleConnector.State.READY) {
            connector?.sendChannelQuery()
        } else {
            Toast.makeText(this, getString(R.string.need_connect_first), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 顶部下拉刷新：列表停在顶部时继续下拉，重发 0x4E29 全量查询重新读取频率。
     * 查询完成（BleConnector.queryGen 自增）后收起刷新动画；customChannels 回显会
     * 经 observeData 自动刷新列表与头部当前信道。5s 超时兜底，防 spinner 卡住。
     */
    private fun setupPullToRefresh() {
        binding.channelSwipe.setColorSchemeColors(0xFF28AA9A.toInt())
        binding.channelSwipe.setOnRefreshListener {
            val c = connector
            if (c == null || c.state.value != BleConnector.State.READY) {
                binding.channelSwipe.isRefreshing = false
                Toast.makeText(this, getString(R.string.need_connect_first), Toast.LENGTH_SHORT).show()
                return@setOnRefreshListener
            }
            val gen = c.queryGen.value
            c.sendChannelQuery()
            lifecycleScope.launch {
                withTimeoutOrNull(5000) { c.queryGen.first { it != gen } }
                binding.channelSwipe.isRefreshing = false
            }
        }
    }

    /**
     * 双指控制：拖拽按住卡片期间，另一根手指上下滑动直接滚动列表。
     * 用户原话："大范围移动时很麻烦，例如我想把 15 挪到 1 得把手指搓到屏幕顶部，速度也
     * 不好控制…比如我按住一个卡片可以用另一只手指上下滑动来控制页面"。
     * ItemTouchHelper 每帧用 `mSelectedStartY + mDy - itemView.getTop()` 重算被拖卡的
     * translation，列表滚动时自动补偿——卡片始终钉在按住它的手指下，所以这里直接
     * scrollBy 即可，无需手动补偿。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 长按 2 秒起拖的「取消」只能在这里做：把手的 OnTouchListener 返回 false，DOWN 由行
        // 自身消费后 MOVE/UP 不会再分发给子视图（注释见 ChannelAdapter.cancelPendingDrag），
        // 把手里的 ACTION_UP 分支收不到事件 —— 实测短按 1.2 秒抬起也会在 2 秒时被起拖。
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> adapter.cancelPendingDrag()
            MotionEvent.ACTION_MOVE -> {
                if (adapter.isPendingDrag()) {
                    val dx = ev.rawX - (adapter.pendingDownRawX() ?: ev.rawX)
                    val dy = ev.rawY - (adapter.pendingDownRawY() ?: ev.rawY)
                    // 按住把手前在滚动列表：手指已经滑开说明不是要拖拽，取消本次起拖
                    if (dx * dx + dy * dy > pendingHoldSlop * pendingHoldSlop) adapter.cancelPendingDrag()
                }
            }
        }
        if (slotDragActive) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    val idx = ev.actionIndex
                    secondPointerId = ev.getPointerId(idx)
                    secondPointerLastY = ev.getY(idx)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (secondPointerId != -1) {
                        val idx = ev.findPointerIndex(secondPointerId)
                        if (idx >= 0) {
                            val y = ev.getY(idx)
                            val dy = y - secondPointerLastY
                            secondPointerLastY = y
                            // 手指往下 → 内容跟着往下（看更早的行），与普通列表手势一致
                            binding.channelList.scrollBy(0, -dy.toInt())
                        }
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (ev.getPointerId(ev.actionIndex) == secondPointerId) secondPointerId = -1
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> secondPointerId = -1
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun observeData() {
        lifecycleScope.launch {
            ConnectorHolder.connector?.customChannels?.collect { list ->
                customList.clear()
                customList.addAll(list)
                // 排序执行中不重排列表：0x4E2B 回显会带来中间态，
                // 保持用户拖拽后的排列直到进度框结束（finish/abort 时统一同步）
                if (showCustom && !reorderActive) refreshList()
            }
        }
        lifecycleScope.launch {
            ConnectorHolder.connector?.channels?.collect { list ->
                list.getOrNull(slotIndex)?.let {
                    currentCh = it
                    showCurrent(it)
                    // 当前频道行主题青绿高亮（应用户要求统一主题色 #28AA9A；设备端原版为 #0076FF）
                    adapter.current = it
                    refreshPresetHighlight()
                }
            }
        }
        lifecycleScope.launch {
            // 删除结果反馈（0x4E2B 确认）：成功时列表已由 connector 原位同步刷新，
            // 这里只负责提示与解除防抖；失败时明确告知，避免用户误以为未删而重复操作。
            // 排序执行中，所有增删改 ACK 统一路由给排序状态机（先消费，避免其它分支拿到）
            ConnectorHolder.connector?.channelOpResult?.collect { op ->
                if (op == null) return@collect
                if (reorderActive) {
                    ConnectorHolder.connector?.consumeChannelOpResult()
                    binding.root.removeCallbacks(reorderTimeoutRunnable)
                    onReorderAck(op)
                    return@collect
                }
                if (!pendingDeleteUi) return@collect
                pendingDeleteUi = false
                binding.root.removeCallbacks(deleteUiTimeoutRunnable)
                val ok = op.result == SealProtos.ChResult.OK
                Toast.makeText(this@ChannelSettingActivity,
                    getString(if (ok) R.string.channel_op_success else R.string.channel_op_failed),
                    Toast.LENGTH_SHORT).show()
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
        binding.tabCustom.setTextColor(if (custom) 0xFF28AA9A.toInt() else 0xFF888888.toInt())
        binding.tabPreset.setTextColor(if (custom) 0xFF888888.toInt() else 0xFF28AA9A.toInt())
        binding.channelList.visibility = if (custom) View.VISIBLE else View.GONE
        // SwipeRefreshLayout 是 channelList 的父容器（weight=1）：只把列表 GONE 的话，
        // 空的刷新容器仍占一半高度，预设宫格被挤到下半屏、上方一大块空白（用户实测）
        binding.channelSwipe.visibility = if (custom) View.VISIBLE else View.GONE
        binding.presetPanel.visibility = if (custom) View.GONE else View.VISIBLE
        // 设备端预设页没有新增/整理按钮；整理模式下底部让位给整理操作栏
        binding.fabRow.visibility = if (custom && !organizeMode) View.VISIBLE else View.GONE
        binding.organizeBar.visibility = if (custom && organizeMode) View.VISIBLE else View.GONE
        if (custom) refreshList() else refreshPresetHighlight()
    }

    /** 列表内容统一入口：整理模式下列表由本地暂存排列驱动，不被设备回显覆盖 */
    private fun refreshList() {
        if (organizeMode) {
            updateOrganizeBar()
            return
        }
        adapter.submit(customList)
        // 数据到达后列表才有子视图：此时才量得到行高，把底部常驻内边距补上"一行高度"
        // （onCreate 时列表为空，rowH 取到 0，只留了按钮 clearance）
        binding.channelList.post { refreshListBottomPadding() }
    }

    /** 找一个未占用的自定义信道号（真机实测：设备接受 0 号槽写入，0..19 共 20 槽） */
    private fun nextFreeChannelNo(): Int {
        val used = customList.map { it.no }.toSet()
        var n = 0
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
        // 对话框刚关闭的短时窗口内忽略余误触（否则连点的多余点击会激活频道、切走当前信道）。
        // 窗口内被忽略的点击顺延一个新的 400ms 窗口：连点风暴结束后自动恢复，
        // 不会像"整个等待期屏蔽"那样让正常单击长时间失灵（QA 明确要求不要那样做）
        if (listClickGuard) {
            armListClickGuard()
            return
        }
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

    /**
     * 开启短时列表点击屏蔽（LIST_CLICK_GUARD_MS）。
     * 在删除确认对话框关闭时（确定/取消/返回均会触发）调用，
     * 只影响对话框刚关闭那一刻的余误触，不影响之后的正常单击激活。
     */
    private fun armListClickGuard() {
        listClickGuard = true
        binding.root.removeCallbacks(listClickGuardRunnable)
        binding.root.postDelayed(listClickGuardRunnable, LIST_CLICK_GUARD_MS)
    }

    // ---------- 批量整理模式（本地暂存，点保存才写设备） ----------

    /**
     * 进入整理模式：列表切成 20 槽位视图（含空槽占位行），每行显示复选框。
     * 本模式内的一切操作（拖动 / 勾选 / 标记删除）只改内存里的排列，一条协议帧都不发——
     * 用户原话痛点就是「每次拖动都要等一次提交」，整理模式把等待挪到点保存之后。
     */
    private fun enterOrganizeMode() {
        val c = connector
        if (c == null || c.state.value != BleConnector.State.READY) {
            Toast.makeText(this, getString(R.string.need_connect_first), Toast.LENGTH_SHORT).show()
            return
        }
        if (c.isChannelOpPending || reorderActive) {
            Toast.makeText(this, getString(R.string.channel_op_waiting), Toast.LENGTH_SHORT).show()
            return
        }
        if (customList.isEmpty()) {
            Toast.makeText(this, getString(R.string.organize_no_channel), Toast.LENGTH_SHORT).show()
            return
        }
        if (!showCustom) selectTab(true)
        organizeMode = true
        organizeOriginal = customList.toList()
        organizeBaseline = customList.map { it.no }
        organizeChecked.clear()
        organizeDeleted.clear()
        adapter.organizeMode = true
        adapter.checkedNos = emptySet()
        adapter.deletedNos = emptySet()
        adapter.numberByPosition = true      // 20 槽位视图：行号按位置显示，拖动后立刻反映新槽位
        adapter.submit(buildSlotList(customList))
        binding.fabRow.visibility = View.GONE
        binding.organizeBar.visibility = View.VISIBLE
        updateOrganizeBar()
        // 整理栏比 fabRow 高：底部常驻内边距跟着切换，保证最后一槽露在整理栏上方
        binding.root.post { refreshListBottomPadding() }
        log("enterOrganizeMode: 基线=${organizeBaseline}")
    }

    /** 退出整理模式：丢弃暂存，列表回到设备当前数据（本函数从不产生任何写入） */
    private fun exitOrganizeMode() {
        if (!organizeMode) return
        organizeMode = false
        organizeOriginal = emptyList()
        organizeBaseline = emptyList()
        organizeChecked.clear()
        organizeDeleted.clear()
        adapter.organizeMode = false
        adapter.checkedNos = emptySet()
        adapter.deletedNos = emptySet()
        adapter.numberByPosition = false
        binding.organizeBar.visibility = View.GONE
        binding.fabRow.visibility = View.VISIBLE
        binding.root.post { refreshListBottomPadding() }
    }

    /** 底部整理栏：待提交计数 + 「删除所选 / 恢复所选」文案切换 + 保存按钮可用性 */
    private fun updateOrganizeBar() {
        if (!organizeMode) return
        val n = organizeChangeCount()
        binding.organizeCount.text = getString(R.string.organize_pending_changes, n)
        binding.btnOrganizeSave.isEnabled = n > 0
        val allDeleted = organizeChecked.isNotEmpty() && organizeChecked.all { it in organizeDeleted }
        binding.btnOrganizeDelete.setText(
            if (allDeleted) R.string.organize_restore_selected else R.string.organize_delete_selected
        )
        binding.btnOrganizeDelete.isEnabled = organizeChecked.isNotEmpty()
    }

    /** 待提交改动数 = 位置变化的频道数 + 被标记删除的频道数（纯本地计数，与协议无关） */
    private fun organizeChangeCount(): Int {
        var n = 0
        adapter.snapshot().forEachIndexed { i, ch ->
            if (ch.isSlotPlaceholder()) return@forEachIndexed
            if (ch.no in organizeDeleted || ch.no != i) n++
        }
        return n
    }

    /** 复选框勾选/取消（整理期间频道号不变，可作稳定身份） */
    private fun organizeOnCheck(no: Int, checked: Boolean, pos: Int) {
        if (!organizeMode) return
        if (checked) organizeChecked.add(no) else organizeChecked.remove(no)
        adapter.checkedNos = organizeChecked.toSet()
        adapter.notifyRowChanged(pos)
        updateOrganizeBar()
    }

    /** 「删除所选 / 恢复所选」：对当前勾选集合整体标记或取消删除（只是本地划掉标记） */
    private fun organizeToggleDeleteMark() {
        if (!organizeMode) return
        if (organizeChecked.isEmpty()) {
            Toast.makeText(this, getString(R.string.organize_need_select), Toast.LENGTH_SHORT).show()
            return
        }
        val allDeleted = organizeChecked.all { it in organizeDeleted }
        organizeChecked.forEach { no ->
            if (allDeleted) organizeDeleted.remove(no) else organizeDeleted.add(no)
        }
        adapter.deletedNos = organizeDeleted.toSet()
        adapter.notifyDataSetChanged()
        updateOrganizeBar()
        log("organize 标记删除=$organizeDeleted（恢复模式=$allDeleted）")
    }

    /**
     * 整理模式松手：把「成组拖动」落地，并保持零写入。
     * 成组语义：拖动行属于勾选集合且集合>1 时，整组按原相对顺序插到拖动行最终落点处，
     * 也就是「拖一行等于拖一组」。拖动过程本身仍走既有相邻换位，不影响已验证的拖拽链路。
     */
    private fun finishOrganizeDrag() {
        if (!slotDragActive) return
        slotDragActive = false
        // 让位预览清零（此时拖拽已结束、RecoverAnimation 已跑完，全量清零安全）
        for (i in 0 until binding.channelList.childCount) {
            binding.channelList.getChildAt(i).translationY = 0f
        }
        val anchor = slotDragNo
        slotDragNo = -1
        val hoverPos = dragHoverPos
        dragHoverPos = RecyclerView.NO_POSITION
        val arranged = adapter.snapshot()
        if (anchor >= 0 && hoverPos >= 0 && hoverPos < arranged.size) {
            val from = adapter.indexOfNo(anchor)
            if (from >= 0 && from != hoverPos) {
                if (anchor in organizeChecked && organizeChecked.size > 1) {
                    // 成组拖动：整组按【勾选顺序】插到落点处（拖一行等于拖一组）。
                    // organizeChecked 是 LinkedHashSet 保插入序，用户勾 5、12 就该排 5、12
                    // ——按列表顺序取会把用户勾选顺序丢掉（修复 5）
                    val block = organizeChecked.mapNotNull { no -> arranged.find { it.no == no } }
                    val rest = arranged.filter { it.no !in organizeChecked }
                    val insertAt = arranged.take(hoverPos + 1).count { it.no !in organizeChecked }
                    adapter.submit(rest.take(insertAt) + block + rest.drop(insertAt))
                    log("finishOrganizeDrag: 成组拖动 anchor=$anchor 落点=$hoverPos " +
                        "插入位=$insertAt 组(勾选序)=${block.map { it.no }}")
                } else {
                    // 单行：整行搬到落点。列表在拖动期间没动过，这里一次性换位并立即显示新槽位号
                    // （numberByPosition=true：17 拖到 20 就地显示 20，此时零协议写入，等点保存才提交）
                    adapter.moveItem(from, hoverPos)
                    // notifyItemMoved 不重绑 ViewHolder：被拖行与让位行的文字还是旧位号
                    // （用户实测"序号变动预览没有实现"）——必须重绑受影响范围，让 5→3、原 3→4 落地
                    adapter.notifyItemRangeChanged(
                        minOf(from, hoverPos),
                        kotlin.math.abs(from - hoverPos) + 1
                    )
                    log("finishOrganizeDrag: 移动 no=$anchor 从 $from 到 $hoverPos（本地预览，未提交）")
                }
            }
        } else {
            log("finishOrganizeDrag: 无有效落点（拖出列表可视区），放弃，列表保持原样")
        }
        updateOrganizeBar()
    }

    /** 退出前确认（整理模式是纯本地暂存，退出即丢弃，不影响设备） */
    private fun confirmDiscardOrganize(after: () -> Unit) {
        if (organizeChangeCount() == 0) {
            exitOrganizeMode()
            adapter.submit(customList)
            after()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.organize_exit_confirm_title)
            .setMessage(R.string.organize_exit_confirm_msg)
            .setPositiveButton(R.string.reorder_give_up) { _, _ ->
                exitOrganizeMode()
                adapter.submit(customList)
                Toast.makeText(this, getString(R.string.organize_cancelled), Toast.LENGTH_SHORT).show()
                after()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 整理结果的落地计划：先腾空所有「非原地保留」的槽，再按槽位升序插入 */
    private class OrganizePlan(
        val deletes: List<Int>,
        val inserts: List<Pair<Int, SealProtos.ChannelInfo>>
    )

    /**
     * 由整理模式的槽位视图换算落地计划。语义与功能 A 一致：第 i 个槽位上的频道落到 no=i，
     * 占位行表示保持空闲，被标记删除的频道不再写回。
     * 落地顺序：先 DELETE 所有「非原地保留」的槽，再按槽位升序 INSERT——这样每一步 INSERT
     * 的目标槽必然已被上一步腾空，天然规避「INSERT 到占用槽 = 静默覆盖销毁」。
     */
    private fun buildOrganizePlan(): OrganizePlan? {
        val arranged = adapter.snapshot()
        val stay = HashSet<Int>()                 // 原地不动的频道号：既不用删也不用插
        val targets = LinkedHashMap<Int, Int>()   // 频道号 -> 目标槽位
        arranged.forEachIndexed { i, ch ->
            if (ch.isSlotPlaceholder() || ch.no in organizeDeleted) return@forEachIndexed
            if (ch.no == i) stay.add(ch.no) else targets[ch.no] = i
        }
        val device = connector?.customChannels?.value?.filter { !it.isSlotPlaceholder() } ?: return null
        val byNo = device.associateBy { it.no }
        if (targets.keys.any { it !in byNo }) return null      // 数据不一致：不做半成品计划
        val deletes = device.map { it.no }.filter { it !in stay }.sorted()
        val inserts = targets.entries.sortedBy { it.value }
            .map { (no, slot) -> slot to cloneChannel(byNo.getValue(no)).apply { this.no = slot } }
        return OrganizePlan(deletes, inserts)
    }

    /** 保存：先弹确认（写出指令条数，明确告知不要断开），再一次性提交 */
    private fun commitOrganize() {
        if (!organizeMode) return
        val c = connector
        if (c == null || c.state.value != BleConnector.State.READY) {
            Toast.makeText(this, getString(R.string.need_connect_first), Toast.LENGTH_SHORT).show()
            return
        }
        val plan = buildOrganizePlan()
        if (plan == null) {
            Toast.makeText(this, getString(R.string.channel_op_failed), Toast.LENGTH_SHORT).show()
            return
        }
        val total = plan.deletes.size + plan.inserts.size
        if (total == 0) {
            Toast.makeText(this, getString(R.string.organize_nothing_to_commit), Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.organize_confirm_title)
            .setMessage(getString(R.string.organize_confirm_msg,
                total, plan.deletes.size, plan.inserts.size))
            .setPositiveButton(R.string.organize_save) { _, _ -> runOrganizePlan(plan) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 提交整理：预切换激活槽 → 先删后插 → 复用既有排序状态机（进度框 x/y、超时、取消、失败重试剩余） */
    private fun runOrganizePlan(plan: OrganizePlan) {
        val c = connector
        if (c == null || c.state.value != BleConnector.State.READY) {
            Toast.makeText(this, getString(R.string.need_connect_first), Toast.LENGTH_SHORT).show()
            return
        }
        reorderQueue.clear()
        plan.deletes.forEach { reorderQueue.add(MoveStep(it, null)) }
        plan.inserts.forEach { (slot, ch) -> reorderQueue.add(MoveStep(NO_DELETE, ch)) }
        reorderStep = null
        reorderPhase = 0
        reorderSendRetry = 0
        log("organize 提交: 共 ${reorderQueue.size} 条指令 删=${plan.deletes} 插=${plan.inserts.map { it.first }}")
        // 被删除的频道若是 ch1/ch2，先切走（设备禁止删除激活中的频道，DELETE 会被拒 result=3）
        prepareActiveSlotsForReorder(plan.deletes.toSet())
        reorderActive = true
        reorderCancel = false
        reorderTotal = reorderQueue.size
        reorderDone = 0
        binding.organizeBar.visibility = View.GONE
        showReorderDialog()
        if (reorderPreSwitched.isNotEmpty()) {
            // 预切换的激活写刚下发：BLE 写入在上一笔落定前会被 GATT 拒绝（真机实测丢帧）
            binding.root.postDelayed({ if (reorderActive) executeNextReorderStep() },
                PRE_SWITCH_SETTLE_MS)
        } else {
            executeNextReorderStep()
        }
    }

    // ---------- 拖拽排序执行器 ----------

    /**
     * 松手后计算目标排列并执行排序（压缩列表的「插入语义」：保持原 no 集合，按新顺序重新分配）。
     * 槽位视图（拖拽时展开的 20 槽列表）走 [finishSlotDrag]；两者最终都交给 [executeMovePlan]。
     * 安全算法（真机协议实测约束，不许简化）：
     * - MODIFY 改号 = 复制（旧号保留），严禁使用；
     * - INSERT/MODIFY 到占用槽 = 静默覆盖销毁，每步 INSERT 目标槽必须确保空闲。
     */
    private fun planAndExecuteReorder() {
        val c = connector
        if (c == null || c.state.value != BleConnector.State.READY) {
            refreshList()
            return
        }
        val device = c.customChannels.value.filter { !it.isSlotPlaceholder() }
        val arranged = adapter.snapshot().filter { !it.isSlotPlaceholder() }
        if (arranged.size != device.size) {
            refreshList()
            return
        }
        val nos = device.map { it.no }.sorted()
        if (nos.size != device.size) {             // 理论上不会发生（no 唯一）
            refreshList()
            return
        }
        // 目标映射：旧号 -> 新号（第 i 个排列项拿第 i 小的号）
        val target = HashMap<Int, Int>()
        arranged.forEachIndexed { i, ch -> if (ch.no != nos[i]) target[ch.no] = nos[i] }
        if (target.isEmpty()) return               // 无变化
        executeMovePlan(device, target)
    }

    /** 槽位视图：把频道铺到 20 个槽位上，空闲槽补占位行（拖拽时作为可放置目标） */
    private fun buildSlotList(source: List<SealProtos.ChannelInfo>): List<SealProtos.ChannelInfo> {
        val byNo = source.filter { !it.isSlotPlaceholder() }.associateBy { it.no }
        return (0 until ChannelEditActivity.MAX_CHANNEL_SLOTS).map { i ->
            byNo[i] ?: SealProtos.ChannelInfo().also { it.no = i; it.type = SLOT_PLACEHOLDER }
        }
    }

    /**
     * 把手按下：先把列表展开为 20 槽视图（空槽显示为占位行），再启动拖拽。
     * 必须等一帧再 startDrag：submit() 只改数据，ViewHolder 要到下一次布局才绑定到新位置，
     * 直接拿旧 ViewHolder 启动会拖错行（ItemTouchHelper 持有的是 ViewHolder 引用）。
     */
    @Suppress("DEPRECATION")     // ViewHolder.adapterPosition（recyclerview 1.1.0）
    private fun beginSlotDrag(vh: RecyclerView.ViewHolder) {
        val item = adapter.itemAt(vh.adapterPosition) ?: return
        if (item.isSlotPlaceholder() || !showCustom || reorderActive) return
        val c = connector
        if (c == null || c.state.value != BleConnector.State.READY) {
            Toast.makeText(this, getString(R.string.need_connect_first), Toast.LENGTH_SHORT).show()
            return
        }
        if (c.isChannelOpPending) {
            Toast.makeText(this, getString(R.string.channel_op_waiting), Toast.LENGTH_SHORT).show()
            return
        }
        slotDragNo = item.no
        slotDragActive = true
        dragHoverPos = RecyclerView.NO_POSITION
        // 拖拽期间关掉下拉刷新：列表停在顶部时从把手往下拖，刷新手势会和拖拽排序打架。
        // requestDisallowInterceptTouchEvent 给 SwipeRefreshLayout（及祖先）打上
        // FLAG_DISALLOW_INTERCEPT，本次手势内它不再拦截（框架在下个 DOWN 自动清除该 flag，
        // 不影响正常下拉刷新）；isEnabled 双保险。
        binding.channelSwipe.requestDisallowInterceptTouchEvent(true)
        binding.channelSwipe.isEnabled = false
        // 起拖手指位置换算到列表坐标系：后续用「本值 + ItemTouchHelper 的 dY」得到手指当前位置
        val loc = IntArray(2)
        binding.channelList.getLocationOnScreen(loc)
        dragStartYInList = adapter.lastHandleDownRawY - loc[1]
        dragDebugOnce = true
        // fabRow / organizeBar 是 FrameLayout 里绘制在 RecyclerView 之上的浮层：卡片拖到底部
        // 预留空间时会被它们盖住（用户实测"只是加了个图层，往下拖会被这个图层盖住"）。
        // 拖拽期间把底部浮层淡出，松手/取消时复位。
        setBottomBarForDrag(true)
        if (organizeMode) {
            // 整理模式：列表本来就是 20 槽位视图，直接起拖（顺便记下本次是否成组拖动）
            val grouped = item.no in organizeChecked && organizeChecked.size > 1
            log("beginSlotDrag(整理): pos=${vh.adapterPosition} no=${item.no} " +
                "成组=$grouped")
            // 成组拖拽：把勾选集合记到 adapter（onBindViewHolder/updateGroupFollowerPositions
            // 据此让组员行跟在被拖卡下方一起移动）——用户反馈"多选拖动时卡片应排好跟随手指"
            adapter.dragGroupNos = if (grouped) organizeChecked.toSet() else emptySet()
            adapter.dragAnchorNo = if (grouped) item.no else -1
            // 整理模式按位置实时重编号：拖动中 ch.no 仍是原设备号，只有按位置显示才能把
            // 「5 拖到 2-3 中间」就地预览成 3（纯本地、保存前不写设备）
            adapter.numberByPosition = true
            // 记下起拖时卡片的视觉 top：startDrag 后 ItemTouchHelper 的 mSelectedStartY 就是它，
            // 之后「手指位置 = 起拖手指位置 + (当前视觉 top - 本值)」才不受自动滚动污染
            dragStartCardTop = vh.itemView.top.toFloat()
            startDragReveal(vh)
            itemTouchHelper.startDrag(vh)
            return
        }
        // 非整理模式：numberByPosition 保持 false——20 槽位视图里频道行仍显示设备信道号、
        // 空槽占位行显示槽位号，与对讲机屏幕口径一致；用户明确要求非批量模式序号不实时变更
        // （要实时重编号只有整理模式才有意义：那里是保存前的预览）
        val expanded = buildSlotList(adapter.snapshot())
        log("beginSlotDrag: pos=${vh.adapterPosition} no=${item.no} 展开 ${expanded.size} 槽")
        adapter.submit(expanded)
        binding.channelList.post {
            if (!slotDragActive) return@post
            val newPos = adapter.indexOfNo(slotDragNo)
            val holder = binding.channelList.findViewHolderForAdapterPosition(newPos)
            if (holder == null) {
                // 展开后该行不在可见区（理论上不会发生）：放弃本次拖拽，折叠回频道列表
                log("beginSlotDrag: 展开后找不到 no=$slotDragNo 的行（pos=$newPos），放弃本次拖拽")
                collapseSlotDrag()
                return@post
            }
            log("beginSlotDrag: startDrag pos=$newPos type=${holder.itemViewType}")
            dragStartCardTop = holder.itemView.top.toFloat()
            startDragReveal(holder)
            itemTouchHelper.startDrag(holder)
        }
    }

    /** 放弃槽位拖拽：折叠回频道列表，不产生任何写入 */
    private fun collapseSlotDrag() {
        slotDragActive = false
        slotDragNo = -1
        dragHoverPos = RecyclerView.NO_POSITION
        resetDragVisuals()
        adapter.numberByPosition = false
        adapter.submit(adapter.snapshot().filter { !it.isSlotPlaceholder() })
    }

    /**
     * 松手（clearView）：按槽位视图算出「旧号→新号」映射，折叠回频道列表后执行迁移。
     * 语义：第 i 个槽位上的频道就落到 no=i，占位行表示该槽保持空闲。
     * 当拖拽范围内没有空槽时，这个语义与原「插入语义」（中间频道顺移一格）完全等价。
     */
    private fun finishSlotDrag() {
        if (organizeMode) {
            // 整理模式：只改本地排列，零协议写入（本模式存在的全部意义就是不让用户每拖一次等 380ms）
            finishOrganizeDrag()
            return
        }
        if (!slotDragActive) {
            dragHoverPos = RecyclerView.NO_POSITION
            planAndExecuteReorder()
            return
        }
        slotDragActive = false
        // 让位预览清零（此时拖拽已结束、RecoverAnimation 已跑完，全量清零安全）
        for (i in 0 until binding.channelList.childCount) {
            binding.channelList.getChildAt(i).translationY = 0f
        }
        val draggedNo = slotDragNo
        slotDragNo = -1
        val hoverPos = dragHoverPos
        dragHoverPos = RecyclerView.NO_POSITION
        log("finishSlotDrag: 松手 fingerY=$lastFingerY listH=${binding.channelList.height} " +
            "阈值=${(DRAG_CANCEL_MARGIN_DP * resources.displayMetrics.density).toInt()}")
        // 松手才换位：把被拖频道整行搬到蓝色落点处（列表在拖动期间全程没动过）。
        // 落点是空槽时，空槽自然被顶到被拖频道的原位置——「放进那个空槽，原位置空出来」。
        if (draggedNo >= 0 && hoverPos >= 0) {
            val from = adapter.indexOfNo(draggedNo)
            if (from >= 0 && from != hoverPos) {
                log("finishSlotDrag: 落点换位 no=$draggedNo 从 $from 到 $hoverPos")
                adapter.moveItem(from, hoverPos)
            }
        } else {
            log("finishSlotDrag: 无有效落点（拖出列表可视区），放弃本次拖拽，零写入")
        }
        val arranged = adapter.snapshot()
        val target = HashMap<Int, Int>()
        arranged.forEachIndexed { i, item ->
            if (!item.isSlotPlaceholder() && item.no != i) target[item.no] = i
        }
        log("finishSlotDrag: 槽位视图=" + arranged.joinToString(",") {
            if (it.isSlotPlaceholder()) "空" else it.no.toString()
        })
        log("finishSlotDrag: 目标映射(旧号->新号)=$target")
        // 折叠回频道列表：保持新排列（进度框期间看到的就是目标顺序），占位行不再显示
        adapter.numberByPosition = false       // 折叠后是压缩列表，行号必须回到设备信道号
        adapter.submit(arranged.filter { !it.isSlotPlaceholder() })
        if (target.isEmpty()) return
        executeMovePlan(
            connector?.customChannels?.value?.filter { !it.isSlotPlaceholder() } ?: emptyList(),
            target
        )
    }

    /**
     * 执行迁移计划：把「旧号→新号」映射分解成逐步指令后下发。
     * 每步 = DELETE(起点) 等 ACK result=0 → INSERT(目标) 等 ACK result=0。
     * 设备禁止删除激活中的频道（DELETE ch1/ch2 → result=3 FORBIDE，真机实测）：
     * 若 ch1/ch2 指向参与迁移的频道，先切到不动的频道，结束后按内容切回。
     */
    private fun executeMovePlan(device: List<SealProtos.ChannelInfo>, target: Map<Int, Int>) {
        if (target.isEmpty()) {
            refreshList()
            return
        }
        val c = connector
        if (c == null || c.state.value != BleConnector.State.READY || device.isEmpty()) {
            refreshList()
            return
        }
        val steps = buildMoveSteps(device, target)
        if (steps == null) {
            // 需要中转槽但没有（20/20 占满，或仅剩的空闲槽已被本次迁移占用为目标）
            Toast.makeText(this, getString(R.string.reorder_no_free_slot), Toast.LENGTH_SHORT).show()
            refreshList()
            return
        }
        reorderQueue.clear()
        reorderQueue.addAll(steps)
        log("executeMovePlan: 共 ${steps.size} 步 -> " +
            steps.joinToString(" | ") {
                "删${if (it.deleteNo == NO_DELETE) "-" else it.deleteNo}插${it.ch?.no ?: "-"}"
            })

        // 激活槽预处理：ch1/ch2 若指向参与迁移的频道，先切到不动的频道
        // （设备禁止删除激活中的频道，否则第一步 DELETE 就被拒 result=3，真机实测）
        prepareActiveSlotsForReorder(target.keys)
        reorderActive = true
        reorderCancel = false
        reorderTotal = reorderQueue.size
        reorderDone = 0
        showReorderDialog()
        if (reorderPreSwitched.isNotEmpty()) {
            // 预切换的激活写刚下发：BLE 写入在上一笔 onCharacteristicWrite 落定前会被
            // Android GATT 拒绝（连发静默丢帧，真机实测），等写入落定再走第一步
            binding.root.postDelayed({ if (reorderActive) executeNextReorderStep() },
                PRE_SWITCH_SETTLE_MS)
        } else {
            executeNextReorderStep()
        }
    }

    /**
     * 把「旧号→新号」映射分解为逐步迁移指令；返回 null = 无解（需要中转槽却没有空闲槽可用）。
     * 映射来自槽位排列、必然是单射；按槽位连边可分解为两种分量：
     * - 开路 c1→c2→…→ck→(空闲槽)：终点本就空闲，逆序执行即可（先搬最后一个），无需中转槽；
     * - 循环 c1→…→ck→c1：ck 先搬到空闲槽 T，再依次 c_{k-1}→ck、…、c1→c2，最后 T→c1。
     * 两种分量的每一步，INSERT 目标槽都保证刚被上一步的 DELETE 腾空。
     */
    private fun buildMoveSteps(
        device: List<SealProtos.ChannelInfo>,
        target: Map<Int, Int>
    ): List<MoveStep>? {
        val byNo = device.associateBy { it.no }
        if (target.keys.any { it !in byNo }) return null      // 数据不一致：不做半成品计划
        val occupied = device.map { it.no }.toSet()
        val usedTargets = target.values.toSet()
        val steps = ArrayList<MoveStep>()
        val visited = HashSet<Int>()
        var t: Int? = null                         // 中转槽：按需取，开路分量不需要
        for (start in target.keys.sorted()) {
            if (start in visited) continue
            val chain = ArrayList<Int>()
            var isCycle = false
            var x: Int? = start
            // 只沿「该槽的频道也要搬走」的边前进：走到没有后继的槽就是开路终点（空闲槽）
            while (x != null && x !in visited && x in target) {
                visited.add(x)
                chain.add(x)
                val next = target.getValue(x)
                if (next == start) {
                    isCycle = true
                    break
                }
                x = next
            }
            if (chain.isEmpty()) continue
            if (isCycle) {
                // 循环：借 1 个空闲槽 T 中转（T 不能是本次任何迁移的目标槽）
                val slot = t ?: run {
                    val pick = (0 until ChannelEditActivity.MAX_CHANNEL_SLOTS)
                        .firstOrNull { it !in occupied && it !in usedTargets }
                        ?: return null
                    t = pick
                    pick
                }
                val ck = chain.last()
                // ck 的频道搬到中转槽 T
                steps.add(MoveStep(ck, cloneChannel(byNo.getValue(ck)).apply { no = slot }))
                // 依次 c_{k-1}→ck、…、c1→c2（每步目标槽刚被上一步腾空）
                for (i in chain.size - 2 downTo 0) {
                    steps.add(
                        MoveStep(chain[i],
                            cloneChannel(byNo.getValue(chain[i])).apply { no = chain[i + 1] })
                    )
                }
                // 最后 T 上的频道搬到 c1
                steps.add(MoveStep(slot, cloneChannel(byNo.getValue(ck)).apply { no = chain[0] }))
            } else {
                // 开路：终点槽本就空闲，逆序搬（最后一个先搬），不需要中转槽
                var dest = target.getValue(chain.last())
                for (i in chain.size - 1 downTo 0) {
                    steps.add(MoveStep(chain[i],
                        cloneChannel(byNo.getValue(chain[i])).apply { no = dest }))
                    dest = chain[i]
                }
            }
        }
        return if (steps.isEmpty()) null else steps
    }

    /**
     * 排序前激活槽预处理：记录 ch1/ch2 原始频道；若某槽频道参与排序（no 在 moved 中），
     * 先把该槽切到不参与排序的用户频道（全都在动则切公众频道 0），
     * 避开设备「禁止删除激活频道」限制。排序结束后由 restoreActiveSlots 切回。
     * 激活必须逐条延时下发——BLE 写入在上一笔落定前会被拒（真机实测连发丢帧）。
     */
    private fun prepareActiveSlotsForReorder(moved: Set<Int>) {
        val c = connector ?: return
        reorderOriginalActive.clear()
        reorderPreSwitched.clear()
        if (c.deviceMode.value in 3..6) return     // 扫描/组队模式设备禁止激活，不发临时切换
        val unmoved = c.customChannels.value.filter { it.no !in moved }
        val usedTemps = mutableSetOf<Int>()
        val actions = mutableListOf<Pair<SealProtos.ChannelInfo, Boolean>>()
        c.channels.value.forEachIndexed { idx, ch ->
            if (ch == null || ch.type != SealProtos.ChType.USERDEF) return@forEachIndexed
            reorderOriginalActive.add(idx to ch)
            if (ch.no in moved) {
                reorderPreSwitched.add(idx)
                // 两个槽尽量切到不同的不动的频道（全被占用/全在动才回落公众 0）
                val temp = unmoved.firstOrNull { it.no !in usedTemps } ?: SealProtos.ChannelInfo().also {
                    it.no = 0
                    it.type = SealProtos.ChType.PUBLIC
                    it.name = getString(R.string.preset_channel_name, 1)
                }
                usedTemps.add(temp.no)
                actions.add(temp to (idx == 0))
            }
        }
        sendActivationsSerially(actions)
    }

    /**
     * 串行下发 0x4E27 激活：BLE 写入在上一笔 onCharacteristicWrite 落定前会被 Android GATT
     * 拒绝并返回 false（send() 无日志、静默丢帧——真机实测两条连发只出一条）。
     * 逐条延时 ACTIVATION_SEND_GAP_MS；轮次过期的延时任务丢弃，避免误发。
     */
    private fun sendActivationsSerially(actions: List<Pair<SealProtos.ChannelInfo, Boolean>>) {
        if (actions.isEmpty()) return
        val round = ++activationRound
        connector?.sendChannelActivate(actions[0].first, actions[0].second)
        actions.drop(1).forEachIndexed { i, (ch, isMajor) ->
            binding.root.postDelayed({
                if (activationRound == round) {
                    connector?.sendChannelActivate(ch, isMajor)
                }
            }, ACTIVATION_SEND_GAP_MS * (i + 1))
        }
    }

    /** 执行下一步（DELETE→等 ACK→INSERT→等 ACK→下一步）。ACK 驱动的续跳必须 post，
     *  避开 0x4E2B 在 handleChannel 内重入派发时 connector 防抖标志尚未清除的窗口 */
    private fun executeNextReorderStep() {
        val c = connector
        if (reorderCancel) {
            finishReorder(cancelled = true)
            return
        }
        if (c == null || c.state.value != BleConnector.State.READY) {
            abortReorder()
            return
        }
        if (reorderStep == null) {                 // 取下一步；发送重试沿用当前步
            val step = if (reorderQueue.isEmpty()) null else reorderQueue.removeFirst()
            if (step == null) {
                finishReorder(cancelled = false)
                return
            }
            reorderStep = step
            reorderSendRetry = 0
        }
        val step = reorderStep ?: run {
            abortReorder()
            return
        }
        val ch = step.ch
        // 批量整理的「纯插入」步骤：不需要删除阶段，直接进入插入阶段
        if (step.deleteNo == NO_DELETE) {
            if (ch == null) {                       // 空步骤（理论上不会发生），直接跳过
                reorderStep = null
                binding.root.post { if (reorderActive) executeNextReorderStep() }
                return
            }
            reorderPhase = 2
            if (!c.insertChannel(ch)) {
                if (!retrySendReorderStep()) abortReorder()
                return
            }
            armReorderTimeout()
            return
        }
        reorderPhase = 1
        val del = SealProtos.ChannelInfo().also {
            it.no = step.deleteNo
            it.type = SealProtos.ChType.USERDEF
        }
        if (!c.deleteChannel(del)) {
            if (!retrySendReorderStep()) abortReorder()
            return
        }
        armReorderTimeout()
    }

    /**
     * 写通道瞬时被占（上一笔写入未落定）：短延后重发同一步，最多 3 次，
     * 避免把可恢复的写入拥塞误判成失败（预切换激活后第一步必中）。返回是否安排了重试。
     */
    private fun retrySendReorderStep(): Boolean {
        if (reorderSendRetry >= REORDER_SEND_MAX_RETRY) return false
        reorderSendRetry++
        binding.root.removeCallbacks(reorderTimeoutRunnable)
        binding.root.postDelayed({ if (reorderActive) executeNextReorderStep() },
            REORDER_SEND_RETRY_MS)
        return true
    }

    /** 排序步骤 ACK 状态机 */
    private fun onReorderAck(op: SealProtos.ChannelOperate) {
        if (op.result != SealProtos.ChResult.OK) {
            failReorderStep()
            return
        }
        val c = connector ?: run {
            abortReorder()
            return
        }
        when (reorderPhase) {
            1 -> {
                val step = reorderStep ?: run {
                    abortReorder()
                    return
                }
                val ch = step.ch
                if (ch == null) {       // 纯删除步骤（批量整理）：无插入阶段，本步到此结束
                    reorderDone++
                    updateReorderProgress()
                    reorderStep = null
                    binding.root.post { if (reorderActive) executeNextReorderStep() }
                    return
                }
                reorderPhase = 2
                binding.root.post {
                    if (!reorderActive) return@post
                    if (!c.insertChannel(ch)) abortReorder() else armReorderTimeout()
                }
            }
            2 -> {
                reorderDone++
                updateReorderProgress()
                reorderStep = null            // 本步完成，下一步取新步骤
                binding.root.post {
                    if (reorderActive) executeNextReorderStep()
                }
            }
        }
    }

    private fun armReorderTimeout() {
        binding.root.removeCallbacks(reorderTimeoutRunnable)
        binding.root.postDelayed(reorderTimeoutRunnable, REORDER_STEP_TIMEOUT_MS)
    }

    /** 断连/无剩余可重试时的终态：停止后续步骤，提示退出重进页面刷新真实状态（不中途自动重查） */
    private fun abortReorder() {
        if (!reorderActive) return
        reorderActive = false
        reorderQueue.clear()
        binding.root.removeCallbacks(reorderTimeoutRunnable)
        dismissReorderDialog()
        // 排序前的临时切换切回原频道（按内容找当前位置；已删除/找不到则跳过）
        restoreActiveSlots()
        // 整理模式提交失败：设备已处于中间态，暂存失去意义，退出整理让设备数据说话
        exitOrganizeMode()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.channel_op_failed)
            .setMessage(getString(R.string.reorder_failed_msg, reorderDone + 1))
            .setPositiveButton(android.R.string.ok, null)
            .show()
        // 列表同步到设备已确认的真实状态（部分步骤可能已生效）
        refreshList()
    }

    /**
     * 单步失败（ACK 非 OK 或超时）：立即停止后续步骤，但**保留剩余计划**，
     * 让用户选「重试剩余」或「放弃」。原数据 [organizeOriginal] 留内存，
     * 重试从断点继续、不重头再来（批量整理几十条指令时这是唯一可接受的失败体验）。
     */
    private fun failReorderStep() {
        if (!reorderActive) return
        reorderActive = false
        binding.root.removeCallbacks(reorderTimeoutRunnable)
        dismissReorderDialog()
        // 当前步若已过了删除阶段，重试时只补插入，避免对已腾空的槽重复 DELETE
        val step = reorderStep
        if (step != null) {
            reorderQueue.addFirst(if (reorderPhase >= 2) MoveStep(NO_DELETE, step.ch) else step)
            reorderStep = null
        }
        reorderPhase = 0
        reorderSendRetry = 0
        val remaining = reorderQueue.size
        val ready = connector?.state?.value == BleConnector.State.READY
        log("failReorderStep: 已完成 $reorderDone，剩余 $remaining，READY=$ready")
        if (remaining == 0 || !ready) {
            // 没有可重试的剩余（或已断连）：沿用原来的「退出重进刷新真实状态」提示
            abortReorder()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.channel_op_failed)
            .setMessage(getString(R.string.reorder_failed_remaining, reorderDone + 1, remaining))
            .setPositiveButton(R.string.reorder_retry_remaining) { _, _ -> resumeReorder() }
            .setNegativeButton(R.string.reorder_give_up) { _, _ ->
                reorderQueue.clear()
                restoreActiveSlots()
                exitOrganizeMode()
                refreshList()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 「重试剩余」：按剩余队列继续下发。不再重复 prepareActiveSlotsForReorder——
     * 上一轮的预切换仍然生效，且切换目标本来就不在删除集合里，重复调用反而会
     * 把「临时频道」记成原始激活频道、结束后切错。
     */
    private fun resumeReorder() {
        val c = connector
        if (c == null || c.state.value != BleConnector.State.READY || reorderQueue.isEmpty()) {
            abortReorder()
            return
        }
        log("resumeReorder: 剩余 ${reorderQueue.size} 条，已完成 $reorderDone 条")
        reorderActive = true
        reorderCancel = false
        reorderStep = null
        reorderPhase = 0
        reorderSendRetry = 0
        reorderTotal = reorderDone + reorderQueue.size
        showReorderDialog()
        updateReorderProgress()
        executeNextReorderStep()
    }

    private fun finishReorder(cancelled: Boolean) {
        reorderActive = false
        reorderCancel = false
        reorderQueue.clear()
        binding.root.removeCallbacks(reorderTimeoutRunnable)
        dismissReorderDialog()
        // 完成或停在安全点后，都把 ch1/ch2 按新 no 重新激活，避免激活槽漂移到别的频道
        restoreActiveSlots()
        val wasOrganize = organizeMode
        if (wasOrganize) exitOrganizeMode()
        // 同步列表到设备最终状态（排序期间回显被抑制，这里统一刷新）
        refreshList()
        Toast.makeText(
            this,
            getString(
                when {
                    cancelled -> R.string.reorder_cancelled
                    wasOrganize -> R.string.organize_saved
                    else -> R.string.channel_op_success
                }
            ),
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * 排序后恢复 ch1/ch2 激活：设备按 no 记录激活槽（0x4E27 帧带 no），
     * 重排后原 no 可能已属别的频道。把 App 侧记录的激活频道按新 no 重新下发 0x4E27。
     */
    /**
     * 排序后（或中止后）恢复 ch1/ch2：设备按 no 记录激活槽（0x4E27 帧带 no），
     * 重排后原 no 可能已属别的频道；排序前的临时切换也必须切回。
     * 按内容（除 no 外全字段）在设备当前表中找同一频道的新位置，重发 0x4E27。
     * 未移动的频道重发同 no 是无害空操作；找不到（异常）则跳过。
     */
    private fun restoreActiveSlots() {
        val c = connector ?: return
        if (c.deviceMode.value in 3..6) return    // 扫描/组队模式设备禁止激活
        val list = c.customChannels.value
        val actions = mutableListOf<Pair<SealProtos.ChannelInfo, Boolean>>()
        reorderOriginalActive.forEach { (idx, ch) ->
            val newCh = list.firstOrNull { sameChannelContent(it, ch) } ?: return@forEach
            // 未参与排序且未被临时切走的槽：原样不动，不发多余激活
            if (newCh.no == ch.no && idx !in reorderPreSwitched) return@forEach
            actions.add(newCh to (idx == 0))
        }
        sendActivationsSerially(actions)
    }

    /** 频道内容相同（除 no 外全字段）——用于排序后按内容找回同一频道的新位置 */
    private fun sameChannelContent(a: SealProtos.ChannelInfo, b: SealProtos.ChannelInfo): Boolean =
        a.name == b.name && a.rxFreq == b.rxFreq && a.txFreq == b.txFreq &&
            a.rxCss == b.rxCss && a.txCss == b.txCss

    private fun showReorderDialog() {
        reorderDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.reorder_title)
            .setMessage(getString(R.string.reorder_progress, 0, reorderTotal))
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> reorderCancel = true }
            .show()
    }

    private fun updateReorderProgress() {
        reorderDialog?.setMessage(getString(R.string.reorder_progress, reorderDone, reorderTotal))
    }

    private fun dismissReorderDialog() {
        reorderDialog?.dismiss()
        reorderDialog = null
    }

    private fun cloneChannel(ch: SealProtos.ChannelInfo): SealProtos.ChannelInfo =
        SealProtos.ChannelInfo().also {
            it.no = ch.no
            it.type = ch.type
            it.rxFreq = ch.rxFreq
            it.txFreq = ch.txFreq
            it.name = ch.name
            it.rxCss = ch.rxCss
            it.txCss = ch.txCss
        }

    /** 长按列表项（设备端 b(View,int,channel)）：三选项对话框 [删除/编辑/取消]，
     *  标题含序号+名称，正文显示该频道 RX/TX 频率。
     *  注：不用 setItems——Material3 主题下普通列表项渲染塌陷（真机实测），
     *  单选列表可用但这里需要自定义行为，改用自定义视图列表（与设备端视觉一致） */
    private fun showChannelOptions(ch: SealProtos.ChannelInfo, position: Int) {
        val title = "%02d %s".format(ch.no + 1, ch.name.ifEmpty { "CH ${ch.no + 1}" })
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

    /**
     * 删除前二级确认（设备端同样有确认对话框）。
     * 删除确认（0x4E2B result=CH_OK）到达后由 connector 原位从列表移除并刷新 UI，
     * 同时本页经 channelOpResult 提示结果；等待确认期间重复点击被忽略（防抖，
     * 防止重复下发删除指令把相邻频道一并删掉——真机反馈 Bug）。
     */
    private fun confirmDeleteChannel(ch: SealProtos.ChannelInfo) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.delete_channel_confirm))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val c = connector ?: return@setPositiveButton
                if (c.state.value != BleConnector.State.READY) {
                    Toast.makeText(this, getString(R.string.need_connect_first), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // 防抖：上一条增删改尚未确认时忽略本次点击
                // （BleConnector.sendChannelOp 也会拒绝，这里是 UI 层双保险）。
                // 必须给出提示——静默返回会让用户以为"点了没反应"而反复点击，
                // 与本次修复的 Bug 体验同源（QA 二轮提出）
                if (c.isChannelOpPending) {
                    Toast.makeText(this, getString(R.string.channel_op_waiting),
                        Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (c.deleteChannel(ch)) {
                    pendingDeleteUi = true
                    binding.root.removeCallbacks(deleteUiTimeoutRunnable)
                    binding.root.postDelayed(deleteUiTimeoutRunnable, 10_000L)
                } else {
                    Toast.makeText(this, getString(R.string.channel_op_failed), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { dialog ->
                // 对话框关闭（确定/取消/返回）后开启短时点击屏蔽，
                // 拦住连点残留落在列表行上的误激活
                dialog.setOnDismissListener { armListClickGuard() }
            }
    }

    override fun onDestroy() {
        // 清理防抖/屏蔽/排序的定时回调，避免页面销毁后残留
        if (::binding.isInitialized) {
            binding.root.removeCallbacks(deleteUiTimeoutRunnable)
            binding.root.removeCallbacks(listClickGuardRunnable)
            binding.root.removeCallbacks(reorderTimeoutRunnable)
        }
        reorderDialog?.dismiss()
        super.onDestroy()
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
        private val onLongClick: (SealProtos.ChannelInfo, Int) -> Unit,
        private val onStartDrag: (RecyclerView.ViewHolder) -> Unit = {},
        private val onCheck: (Int, Boolean, Int) -> Unit = { _, _, _ -> }
    ) : RecyclerView.Adapter<ChannelAdapter.VH>() {

        private val items = mutableListOf<SealProtos.ChannelInfo>()

        /** 整理模式：显示复选框、停用点击/长按（避免误激活频道） */
        var organizeMode = false
            set(value) {
                if (field != value) {
                    field = value
                    notifyDataSetChanged()
                }
            }

        /** 整理模式被勾选的频道号 */
        var checkedNos: Set<Int> = emptySet()

        /** 整理模式被标记删除的频道号（划掉标记） */
        var deletedNos: Set<Int> = emptySet()

        /**
         * 行号显示口径切换：true = 按行位置显示（20 槽位视图，位置即槽位号）；
         * false = 按设备信道号显示（压缩列表，空号不占行）。
         * 整理模式拖到一半时列表里 ch.no 仍是原设备号，只有按位置显示才能把
         * 「17 拖到 20」就地预览成 20（保存前不写设备）。
         */
        var numberByPosition = false

        /**
         * 拖拽让位预览（占用卡落点）：onBindViewHolder 给区间内的新绑定行直接补位移。
         * 拖拽自动滚动时区间内的行可能被回收重绑，不补位移缺口会断。
         */
        var gapPreview: GapPreview? = null

        /**
         * 成组拖拽进行中的频道号集合（含锚点）。空 = 非成组拖拽。
         * 刻意**不**触发 notifyDataSetChanged：拖拽中 notify 会命中
         * RecyclerView#assertNotInLayoutOrScroll 崩溃（见 clearView 注释）——
         * 可见行由 Activity#beginSlotDrag 就地刷样式，滚动回收重绑走 onBindViewHolder。
         */
        var dragGroupNos: Set<Int> = emptySet()

        /** 成组拖拽的锚点（被拖着走的那一行）频道号；-1 = 非成组拖拽 */
        var dragAnchorNo = -1

        /** 最近一次把手按下的手指 y（屏幕坐标 rawY）：换算成列表坐标后用于判「拖出列表」 */
        var lastHandleDownRawY = 0f

        /** 长按中的待启动拖拽把手：DOWN 后延迟 [DRAG_HOLD_MS] 才真正起拖（防误触），
         *  期间抬手则取消。见 chDrag 的 OnTouchListener 与 Activity#dispatchTouchEvent。 */
        private var pendingDragHolder: RecyclerView.ViewHolder? = null

        /** 长按等待期间被按住的把手视图（[dragHoldRunnable] 的宿主，取消回调要用它） */
        private var pendingDragView: View? = null

        /** 长按等待期间手指按下位置（raw 坐标）：移动超过 [DRAG_HOLD_CANCEL_SLOP_DP]
         *  就当成滚动手势、取消起拖 */
        private var pendingDragDownX = 0f
        private var pendingDragDownY = 0f

        /** 长按到点（[DRAG_HOLD_MS]，现 1 秒）：真正启动拖拽（防误触的长按等待） */
        private val dragHoldRunnable = Runnable {
            val h = pendingDragHolder ?: return@Runnable
            val v = pendingDragView
            pendingDragHolder = null
            pendingDragView = null
            v?.animate()?.cancel()
            v?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(120L)?.start()
            // 起拖成功给一次触感反馈（把手那条 2 秒长按由 App 自己计时，系统不给反馈）
            h.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onStartDrag(h)
        }

        /**
         * 取消「长按等待起拖」状态：移除计时回调 + 把手视觉归位。
         *
         * 必须由 Activity#dispatchTouchEvent 调用：把手的 OnTouchListener 返回 false，
         * DOWN 之后由行自身消费事件，按 Android 的分发规则 MOVE/UP **不会再分发给子视图**
         * （RecyclerView 那条 path 也是同样的坑，见 clearView 注释），所以把手里的
         * ACTION_UP 分支在绝大多数情况下根本收不到，指望它取消计时一定会漏。
         */
        fun cancelPendingDrag() {
            pendingDragHolder = null
            val v = pendingDragView
            pendingDragView = null
            if (v == null) return
            v.removeCallbacks(dragHoldRunnable)
            v.animate().cancel()
            v.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
        }

        /** 当前是否处于「按下把手、等待 [DRAG_HOLD_MS]」状态 */
        fun isPendingDrag(): Boolean = pendingDragView != null

        /** 长按起拖等待中的按下点（raw 坐标），未等待时返回 null */
        fun pendingDownRawY(): Float? = if (pendingDragView != null) pendingDragDownY else null
        fun pendingDownRawX(): Float? = if (pendingDragView != null) pendingDragDownX else null

        /** 本次触摸序列是否从拖拽把手发起：把手的 DOWN 期间屏蔽行的点击与长按菜单
         *  （长按把手 2 秒启动拖拽；不屏蔽会把把手上的 tap 当成激活频道、把按住当成选项菜单）
         *  复位时机：把手的 UP/CANCEL（未启动拖拽时）或 clearView（拖拽结束后，
         *  此时 RecyclerView 已拦截事件流、把手收不到 UP）——见 endHandleGesture */
        private var handleGesture = false

        /** 当前激活频道（设备端 SealSharkChannel.equals：chType+chNo 匹配），
         *  命中行 RX/TX 频率文字主题青绿高亮（应用户要求统一主题色 #28AA9A；设备端原版为 #0076FF） */
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

        fun itemAt(pos: Int): SealProtos.ChannelInfo? = items.getOrNull(pos)

        /** 找某个设备频道号当前所在的行（展开成槽位视图后位置会变） */
        fun indexOfNo(no: Int): Int = items.indexOfFirst { !it.isSlotPlaceholder() && it.no == no }

        override fun getItemViewType(position: Int): Int =
            if (items[position].isSlotPlaceholder()) VIEW_TYPE_SLOT else VIEW_TYPE_CHANNEL

        /** 拖拽过程中移动一项（相邻交换累积成最终排列） */
        fun moveItem(from: Int, to: Int) {
            if (from == to || from !in items.indices || to !in items.indices) return
            val it = items.removeAt(from)
            items.add(to, it)
            notifyItemMoved(from, to)
        }

        /** 解除把手手势屏蔽（由 ItemTouchHelper.Callback#clearView 调用，见其注释） */
        fun endHandleGesture() {
            handleGesture = false
        }

        /** 行底色基准（拖拽高亮擦除时用它恢复：整理模式里被勾选的行本身是浅蓝底） */
        fun rowBaseColor(pos: Int): Int {
            val ch = items.getOrNull(pos) ?: return 0xFFFFFFFF.toInt()
            return if (organizeMode && ch.no in checkedNos) 0xFF3328AA9A.toInt() else 0xFFFFFFFF.toInt()
        }

        /** 单行刷新（勾选/删除标记变化时用，避免整表 notify 打断拖拽） */
        fun notifyRowChanged(pos: Int) {
            if (pos in items.indices) notifyItemChanged(pos)
        }

        /** 当前排列快照（对象仍带原设备信道号，供松手后计算目标映射） */
        fun snapshot(): List<SealProtos.ChannelInfo> = items.toList()

        class VH(val b: ItemChannelBinding) : RecyclerView.ViewHolder(b.root)

        companion object {
            const val VIEW_TYPE_CHANNEL = 0
            /** 空槽占位行（拖拽时的可放置目标） */
            const val VIEW_TYPE_SLOT = 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ch = items[position]
            // 让位预览：拖拽中若本行处于让位区间，直接补位移（自动滚动时区间内的行可能被
            // 回收重绑，不补的话缺口会断；直接设无动画——过渡动画只驱动已附着的可见行）。
            // 非拖拽/非区间：把残留偏移复位（否则拖拽结束后这一行会歪着）。
            // 与 applyGapPreview 共用 GapPreview#shiftFor（同一份公式）：区间内整行让位 +
            // 起拖位上下相邻行的撑开缓冲，滚动重绑后缺口才不会和动画路径算出两个值
            // 有效落点时被拖卡片松手瞬间被隐藏（alpha 0，压回位动画）；复用时必须复位，
            // 否则列表重排后该卡片不可见。
            // 成组拖拽的组员行例外：不参与让位（translation 由
            // updateGroupFollowerPositions 每帧接管），alpha 与被拖卡一致（DRAG_ALPHA；
            // 松手复位/重绑兜底用，正常拖拽中每帧会同步成被拖卡的实时 alpha）
            val gap = gapPreview
            val isFollower = ch.no in dragGroupNos && ch.no != dragAnchorNo
            val gapShift = if (!isFollower && gap != null && position != gap.fromPos) {
                gap.shiftFor(position)
            } else 0f
            holder.itemView.translationY = gapShift
            if (isFollower) holder.itemView.translationX = 0f
            holder.itemView.alpha = if (isFollower) DRAG_ALPHA else 1f
            // 槽位视图里位置就是槽位号（整理模式拖动后 ch.no 仍是原设备号，必须按位置显示）
            val dispNo = if (numberByPosition) position else ch.no
            if (ch.isSlotPlaceholder()) {
                // 空槽占位行：虚线空框 + 设备口径序号（no+1）。本身不可拖动、不可点击，
                // 只作为拖拽的落点（ItemTouchHelper#canDropOver 默认 true）。
                // 复用同一个 ViewHolder，必须清掉上一行（频道行）设过的监听器
                holder.b.contentBox.visibility = View.GONE
                holder.b.placeholderBox.visibility = View.VISIBLE
                // 复位落点高亮（复用 ViewHolder：上一行可能正被点亮为蓝色，避免残留）
                holder.b.placeholderBox.setBackgroundResource(R.drawable.bg_slot_placeholder)
                holder.b.slotNo.setTextColor(0xFF90A4AE.toInt())
                holder.b.slotNo.text = (dispNo + 1).toString()
                holder.itemView.setOnClickListener(null)
                holder.itemView.setOnLongClickListener(null)
                holder.b.chDrag.setOnTouchListener(null)
                return
            }
            holder.b.contentBox.visibility = View.VISIBLE
            holder.b.placeholderBox.visibility = View.GONE
            // 设备端显示的就是信道号（1 起，空号照样占行）——实测对讲机屏幕：
            // 槽位有空缺时（如 17/19 空闲）仍按槽位号显示 16/18/20，不是连续列表序号。
            // 因此必须显示 ch.no+1；用 position+1 会把空号抹平、与设备对不上。
            holder.b.chTitle.text = "%02d  %s".format(dispNo + 1, ch.name.ifEmpty { "CH ${dispNo + 1}" })
            holder.b.chRxFreq.text = FreqFormat.format(ch.rxFreq)
            holder.b.chTxFreq.text = FreqFormat.format(ch.txFreq)
            // 整理模式：被标记删除的行划掉 + 半透明；被勾选的行整行浅蓝底；
            // 成组拖拽中、非锚点的勾选行整行隐藏——它们已被"提走"，以层叠队列跟在
            // 被拖卡下方一起移动（drawGroupFollowers 画出来），不能再留在原地
            // （用户反馈：第二第三张被选中的卡片还在原来的位置，效果完全不对）
            val markedDeleted = organizeMode && ch.no in deletedNos
            val inDragGroup = ch.no in dragGroupNos && ch.no != dragAnchorNo
            if (markedDeleted) {
                holder.b.chTitle.paintFlags =
                    holder.b.chTitle.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                holder.b.contentBox.alpha = 0.4f
                holder.itemView.alpha = 1f
            } else if (inDragGroup) {
                holder.b.chTitle.paintFlags =
                    holder.b.chTitle.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                holder.b.contentBox.alpha = 1f
                holder.itemView.alpha = 0f
            } else {
                holder.b.chTitle.paintFlags =
                    holder.b.chTitle.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                holder.b.contentBox.alpha = 1f
                holder.itemView.alpha = 1f
            }
            holder.itemView.setBackgroundColor(
                if (organizeMode && ch.no in checkedNos) 0xFF3328AA9A.toInt() else 0xFFFFFFFF.toInt()
            )
            // 复选框：仅整理模式显示；先清监听器再置态，避免重复 setValue 回调自激
            holder.b.chCheck.visibility = if (organizeMode) View.VISIBLE else View.GONE
            holder.b.chCheck.setOnCheckedChangeListener(null)
            holder.b.chCheck.isChecked = markedDeleted || (organizeMode && ch.no in checkedNos)
            holder.b.chCheck.setOnCheckedChangeListener { _, checked ->
                @Suppress("DEPRECATION")
                onCheck(ch.no, checked, holder.adapterPosition)
            }
            // 当前频道行高亮（应用户要求统一主题色 #28AA9A；设备端原版 #0076FF，否则黑色）
            val isCurrent = current?.let { it.type == ch.type && it.no == ch.no } == true
            val color = if (isCurrent) 0xFF28AA9A.toInt() else 0xFF000000.toInt()
            holder.b.chRxFreq.setTextColor(color)
            holder.b.chTxFreq.setTextColor(color)
            holder.itemView.setOnClickListener { if (!handleGesture) onClick(ch) }
            holder.itemView.setOnLongClickListener {
                if (!handleGesture) {
                    @Suppress("DEPRECATION")
                    onLongClick(ch, holder.adapterPosition)
                }
                true
            }
            // 拖拽把手：按下即启动排序拖拽（ItemTouchHelper 官方模式——OnTouchListener 里
            // ACTION_DOWN 调 startDrag 并返回 false，让事件流经 RecyclerView 的
            // OnItemTouchListener 机制；真机实测：把手里 setOnLongClickListener 启动拖拽时
            // 子视图消费 DOWN 后事件链断裂，onMove 一次都不会来，拖拽完全失效）
            // 复用时可能带着上一次长按没做完的放大动画（行被回收到别的位置），先归位
            if (holder.b.chDrag !== pendingDragView) {
                holder.b.chDrag.animate().cancel()
                holder.b.chDrag.scaleX = 1f
                holder.b.chDrag.scaleY = 1f
            }
            holder.b.chDrag.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        handleGesture = true
                        lastHandleDownRawY = ev.rawY      // 起拖手指位置（屏幕坐标），见 dragStartYInList
                        if (organizeMode) {
                            // 整理模式（批量处理）不需要防误触：勾选走复选框/行本体，
                            // 把手上再等 1 秒纯属拖累（用户反馈）。按下立即起拖，
                            // 只给一次触感反馈；也无须放大动画（没有"等待期"可反馈）。
                            v.animate().cancel()
                            v.scaleX = 1f
                            v.scaleY = 1f
                            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            onStartDrag(holder)
                        } else {
                            // 普通模式长按不放 [DRAG_HOLD_MS]（1 秒）才启动拖拽：把手太小，
                            // 滑动时容易误触（用户实测）。期间抬手（UP/CANCEL）移除回调，
                            // 本次手势就不再起拖。
                            pendingDragHolder = holder
                            pendingDragView = v
                            pendingDragDownX = ev.rawX
                            pendingDragDownY = ev.rawY
                            v.removeCallbacks(dragHoldRunnable)
                            v.postDelayed(dragHoldRunnable, DRAG_HOLD_MS)
                            // 长按期间的视觉反馈：要按住 1 秒才起拖，没有反馈用户会以为没按到。
                            // 把手在这 1 秒内缓慢放大到 1.35 倍；取消（抬手/移动过阈值）时由
                            // cancelPendingDrag 归位。注意：不能在这里 return true 让把手消费事件——
                            // 消费后 ItemTouchHelper 的 OnItemTouchListener 拿不到 DOWN，拖拽完全失效。
                            v.animate().cancel()
                            v.animate()
                                .scaleX(1.35f)
                                .scaleY(1.35f)
                                .setDuration(DRAG_HOLD_MS)
                                .setInterpolator(LinearInterpolator())
                                .start()
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.removeCallbacks(dragHoldRunnable)
                        pendingDragHolder = null
                        // post 到本序列分发完成后再解除屏蔽：行点击/长按在同一序列内触发，必须还能看到标志
                        v.post { handleGesture = false }
                    }
                }
                false
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

        /** 对话框关闭后的列表点击屏蔽时长（QA 要求 300–500ms，取中值） */
        private const val LIST_CLICK_GUARD_MS = 400L

        /** 拖动中被拖卡片的不透明度（用户要求：拖起来要半透明，跟列表其它行区分开） */
        private const val DRAG_ALPHA = 0.5f

        /**
         * 手指超出列表上下边界多少 dp 视为「拖出去了」——松手即取消，不产生任何写入。
         * 取 12dp（≈33px）而不是更大的值：列表底边距屏幕底只有 44px（实测 RV 底 2296 /
         * 屏幕高 2340），阈值再大下方就永远够不到，「拖到最底下取消」形同虚设。
         * 正常拖到最后一个槽位时手指仍在列表内（列表底部预留了一整行安全空间，
         * 末槽滚上来后位于列表中下部），不会被误判。
         */
        private const val DRAG_CANCEL_MARGIN_DP = 12f

        /** 排序单步确认超时（大于 connector 的 10s 兜底，确保其先清理防抖状态） */
        private const val REORDER_STEP_TIMEOUT_MS = 12_000L

        /** 激活串行下发间隔：BLE 写入在上一笔 onCharacteristicWrite 落定前会被拒（真机实测） */
        private const val ACTIVATION_SEND_GAP_MS = 250L

        /** 预切换激活下发后、排序第一步前的写入落定等待（激活写回调实测 ~90ms） */
        private const val PRE_SWITCH_SETTLE_MS = 400L

        /** 排序步骤发送重试：写通道被占时短延重发，最多 3 次（间隔 200ms） */
        private const val REORDER_SEND_RETRY_MS = 200L
        private const val REORDER_SEND_MAX_RETRY = 3

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

/**
 * 排序单步：把 deleteNo 槽位的频道整体搬到 ch.no 指定的新槽位。
 * 每步在设备侧 = DELETE(deleteNo) → ACK result=0 → INSERT(ch) → ACK result=0，
 * 因此 INSERT 目标槽（ch.no）必须保证空闲（由 planAndExecuteReorder 的循环分解 +
 * 中转槽算法确保）。ch 是已按目标号改好的完整频道副本（deleteChannel 只需 no+type，
 * insertChannel 需要全字段）。
 */
/**
 * 一条迁移指令：先 DELETE（可选）再 INSERT（可选）。
 * - 功能 A 的「搬一个频道」= 删旧槽 + 插新槽，两个字段都用上；
 * - 批量整理的「纯删除」= deleteNo >= 0 且 ch == null；
 * - 批量整理的「纯插入」= deleteNo == NO_DELETE(-1) 且 ch != null。
 */
private class MoveStep(
    val deleteNo: Int,
    val ch: SealProtos.ChannelInfo?
)
