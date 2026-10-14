package com.medicfurry.walkieoffline.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.medicfurry.walkieoffline.R
import java.io.File
import java.io.FilenameFilter
import java.util.Locale

/**
 * 固件目录读取 —— 逐句依据实测
 * 固件目录扫描逻辑（依据设备端行为与实测整理）：
 *
 *   accept(dir, name)  [设备端 :27-64]
 *     name != null && name.length() > 12
 *       && name.toLowerCase().startsWith(fs)
 *       && name.toLowerCase().endsWith(fe)
 *
 *   initUI()  [设备端 :66-241]
 *     fs/fe 按设备类型取值（本应用只支持 Shark，即设备端 deviceType==4 分支）：
 *       fs = "shark_"
 *       fe = "_hw" + verHw + ".bin"        （verHw 对应设备硬件版本）
 *     Environment.getExternalStorageState() ∈ { mounted, mounted_ro }
 *       → new File(Environment.getExternalStorageDirectory(), "Mi-walkie-talkie.firmware")
 *       → dir.isDirectory() → dir.listFiles(this)
 *       → Arrays.sort(files) → 取 files[length - 1]（排序后最后一个 = 版本最高）
 *     try/catch(Throwable) 全包，任何异常都当作「没找到」
 *
 * 差异说明（非臆造，仅适配现代系统）：
 *   设备端 targetSdk 低，靠 READ_EXTERNAL_STORAGE 直接走 File API 就能读 /sdcard。
 *   本应用 targetSdk=34，Android 11（SDK 30）起对非媒体文件强制分区存储，
 *   实测 `dumpsys package` 显示 READ_EXTERNAL_STORAGE: restricted=true，
 *   listFiles() 必然返回空 —— 因此必须先拿到「所有文件访问权限」
 *   (MANAGE_EXTERNAL_STORAGE / Environment.isExternalStorageManager())。
 */
object FirmwareManager {

    const val FIRMWARE_DIR_NAME = "Mi-walkie-talkie.firmware"

    /** 设备端实现 —— 固定 128 字节/包，与 MTU 无关 */
    const val PACK_SIZE = 128

    private const val REQ_PICK_FILE = 300

    /** 警告区闪烁：每 500ms（0.5 秒）切换一帧 —— 红图标黑字 ↔ 白图标红底白字 */
    private const val WARN_BLINK_INTERVAL_MS = 500L
    private val warnHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 固件目录路径（设备端 BaseUpdateActivity:200-208） */
    fun firmwareDir(): File =
        File(Environment.getExternalStorageDirectory(), FIRMWARE_DIR_NAME)

    /**
     * Android 11+ 是否具备「所有文件访问权限」。
     * 没有这个权限，设备端的目录扫描逻辑在 targetSdk>=30 上永远返回空。
     */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /** 跳转到本应用的「所有文件访问权限」设置页 */
    fun openAllFilesAccessSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${activity.packageName}")
                )
            )
        }
    }

    /**
     * 设备端 accept() 的过滤器。
     * verHw <= 0（设备没连过、拿不到硬件版本）时退化为只按前缀 + ".bin" 匹配，
     * 避免因为拿不到 hw 而一个文件都列不出来。
     */
    private fun firmwareFilter(verHw: Int): FilenameFilter {
        val prefix = "shark_"
        val suffix = if (verHw > 0) "_hw$verHw.bin" else ".bin"
        return FilenameFilter { _, name ->
            if (name.isNullOrEmpty() || name.length <= 12) false
            else {
                val lower = name.lowercase(Locale.getDefault())
                lower.startsWith(prefix) && lower.endsWith(suffix)
            }
        }
    }

    /** 设备端 initUI():176-241 —— 扫描 + Arrays.sort，异常一律吞掉 */
    fun scanFirmware(verHw: Int): List<File> = try {
        val state = Environment.getExternalStorageState()
        val mounted = state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY
        if (!mounted) emptyList()
        else {
            val dir = firmwareDir()
            if (!dir.isDirectory) emptyList()
            else {
                val files: Array<File> = dir.listFiles(firmwareFilter(verHw)) ?: emptyArray()
                files.sort()          // Arrays.sort → File 按路径自然序
                files.toList()
            }
        }
    } catch (t: Throwable) {
        emptyList()
    }

    /** 设备端取排序后最后一个 */
    fun pickDefault(files: List<File>): File? = files.lastOrNull()

    /**
     * 固件刷写完整流程入口（MainActivity「固件升级」）。
     * @param verHw 设备硬件版本号（BleConnector.deviceParam.verHw），<=0 表示未知
     * @param onPicked 选中固件后的回调（当前阶段只回传所选文件，传输在下一步实现）
     */
    fun requestFirmwareFlow(activity: Activity, verHw: Int, onPicked: (File) -> Unit) {
        if (!hasAllFilesAccess()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.firmware_all_files_title)
                .setMessage(R.string.firmware_all_files_message)
                .setPositiveButton(R.string.firmware_grant_access) { _, _ ->
                    openAllFilesAccessSettings(activity)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        showFirmwareDialog(activity, verHw, onPicked)
    }

    private fun showFirmwareDialog(activity: Activity, verHw: Int, onPicked: (File) -> Unit) {
        val dir = firmwareDir()
        val files = scanFirmware(verHw)
        val dp = activity.resources.displayMetrics.density

        val filterHint = if (verHw > 0)
            activity.getString(R.string.firmware_filter_hint, verHw)
        else
            activity.getString(R.string.firmware_filter_hint_unknown)

        if (files.isEmpty()) {
            val why = if (!dir.isDirectory)
                activity.getString(R.string.firmware_dir_missing, dir.absolutePath)
            else
                activity.getString(R.string.firmware_no_match)
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.firmware_update)
                .setMessage("$why\n\n$filterHint")
                .setNeutralButton(R.string.firmware_select_file) { _, _ -> pickBySaf(activity) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        var selected = pickDefault(files) ?: files[0]

        // 注意：MIUI + Material3 主题下 AlertDialog 的 setItems/setSingleChoiceItems
        // 列表项高度会塌成 0（真机 dump 里只剩一个空的 ScrollView），
        // 所以这里和频道长按菜单一样改用自定义视图渲染固件列表。
        val root = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (20 * dp).toInt()
            setPadding(pad, (12 * dp).toInt(), pad, 0)
        }

        root.addView(android.widget.TextView(activity).apply {
            text = "${dir.absolutePath}\n$filterHint"
            setTextColor(0xFF666666.toInt())
            textSize = 13f
            setPadding(0, 0, 0, (12 * dp).toInt())
        })

        // 固件列表：整块加边框，每行之间用分隔线
        val listBox = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(1, 1, 1, 1)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                setStroke((1 * dp).toInt(), 0xFFCCCCCC.toInt())
                cornerRadius = 4 * dp
            }
        }

        val rows = files.map { file ->
            android.widget.TextView(activity).apply {
                text = file.name
                textSize = 15f
                minHeight = (48 * dp).toInt()
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
            }
        }
        rows.forEachIndexed { index, tv ->
            listBox.addView(tv)
            // 行之间的分隔线（最后一行后面不加）
            if (index < rows.size - 1) {
                listBox.addView(android.view.View(activity).apply {
                    setBackgroundColor(0xFFEEEEEE.toInt())
                }, android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
                ))
            }
        }
        root.addView(listBox)

        fun paintRow() {
            rows.forEachIndexed { i, tv ->
                val on = files[i] == selected
                tv.setTextColor(if (on) 0xFF28AA9A.toInt() else 0xFF000000.toInt())
                tv.text = (if (on) "● " else "○ ") + files[i].name
            }
        }
        paintRow()

        // 已选固件名（按钮空间有限，完整文件名放这里展示）
        val pickedView = android.widget.TextView(activity).apply {
            textSize = 13f
            setPadding(0, (8 * dp).toInt(), 0, 0)
        }
        fun paintPicked() {
            pickedView.text = activity.getString(R.string.firmware_picked_file, selected.name)
        }
        paintPicked()
        root.addView(pickedView)

        // 底部三个按钮：取消 / 选择固件文件 / 刷写
        val footer = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, (10 * dp).toInt(), 0, (4 * dp).toInt())
        }

        fun makeButton(text: String, solid: Boolean): android.widget.Button =
            android.widget.Button(activity).apply {
                this.text = text
                textSize = 13f
                isAllCaps = false
                gravity = android.view.Gravity.CENTER
                setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
                if (solid) {
                    setBackgroundColor(0xFF28AA9A.toInt())
                    setTextColor(0xFFFFFFFF.toInt())
                } else {
                    setBackgroundColor(0x00000000)
                    setTextColor(0xFF666666.toInt())
                }
            }

        val btnCancel = makeButton(activity.getString(android.R.string.cancel), false).apply {
            setTextColor(0xFFD32F2F.toInt())     // 取消：红色
        }
        val btnPick = makeButton(activity.getString(R.string.firmware_select_file), false)
        val btnFlash = makeButton(activity.getString(R.string.firmware_flash), true)

        // 三个按钮统一高度（48dp），避免「刷写」因文件名换行变高
        footer.addView(btnCancel, android.widget.LinearLayout.LayoutParams(
            0, (48 * dp).toInt(), 1f))
        footer.addView(btnPick, android.widget.LinearLayout.LayoutParams(
            0, (48 * dp).toInt(), 1.3f))
        footer.addView(btnFlash, android.widget.LinearLayout.LayoutParams(
            0, (48 * dp).toInt(), 1.3f))
        root.addView(footer)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.firmware_update)
            .setView(root)
            .show()

        rows.forEachIndexed { i, tv ->
            tv.setOnClickListener {
                selected = files[i]
                paintRow()
                paintPicked()
            }
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnPick.setOnClickListener { dialog.dismiss(); pickBySaf(activity) }
        btnFlash.setOnClickListener {
            dialog.dismiss()
            showFlashWarning(activity, selected, onPicked)
        }
    }

    /**
     * 刷机高风险确认弹窗：
     * - 标题「高风险操作警告」也放进自定义视图，与图标、文案一起闪烁（每 500ms 一帧）：
     *   第 1 帧 = 红色警告标识 + 黑色字体（无底框）；
     *   第 2 帧 = 白色警告标识 + 红色底框 + 白色字体。
     * - 取消红色 / 确认蓝色
     * - 确认按钮 10 秒倒计时后才能点击（倒计时期间显示「确认 (Ns)」）
     * - 关于页连点版本号 5 次可关闭倒计时（PrefsManager.bypassFlashCountdown），
     *   关闭后确认按钮立即可点，但警告弹窗本身仍然保留。
     */
    private fun showFlashWarning(activity: Activity, file: File, onPicked: (File) -> Unit) {
        val bypass =
            (activity.application as? com.medicfurry.walkieoffline.MiWalkieApp)
                ?.prefs?.bypassFlashCountdown == true
        val dp = activity.resources.displayMetrics.density

        // ---- 自定义内容：闪烁的警告区（标题 + 图标 + 文案）+ 固件文件名 ----
        val root = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (20 * dp).toInt()
            setPadding(pad, (12 * dp).toInt(), pad, 0)
        }

        val warnRow = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
        }
        val dm = activity.resources.displayMetrics
        // 图标自适应：按屏幕短边的比例缩放，夹在 64~104dp，小屏不挤占文字
        val shortSide = minOf(dm.widthPixels, dm.heightPixels)
        val iconPx = (shortSide * 0.18f).toInt().coerceIn((64 * dp).toInt(), (104 * dp).toInt())
        val warnIcon = android.widget.ImageView(activity).apply {
            setImageResource(R.drawable.flash_warning_icon_red)
            layoutParams = android.widget.LinearLayout.LayoutParams(iconPx, iconPx)
        }

        // 右侧竖排：标题（大字加粗）+ 文案（「谨慎操作」再放大加粗）
        val warnCol = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).also { it.marginStart = (10 * dp).toInt() }
        }
        val titleRaw = activity.getString(R.string.flash_warning_title)
        val warnTitle = android.widget.TextView(activity).apply {
            text = titleRaw.replace("|", "")
            // 标题字号按语言取 dimen：中文 44sp（放大两倍），英文 30sp
            // ——英文 "Warning!" 是单个长单词，44sp 超宽会被硬拆成 "Warnin / g!"
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                activity.resources.getDimension(R.dimen.flash_warn_title_size)
            )
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF000000.toInt())
        }
        // 风险说明正文（先填整句，稍后按可用宽度分块断行）
        val descRaw = activity.getString(R.string.flash_warning_message)
        val descBlocks = descRaw.split("|")

        val warnDesc = android.widget.TextView(activity).apply {
            text = descRaw.replace("|", "")
            textSize = 18f                       // 说明：18sp
            setTextColor(0xFF000000.toInt())
            setLineSpacing(0f, 1.25f)
            setPadding(0, (4 * dp).toInt(), 0, 0)
        }
        // 「谨慎操作！」独立成段，放不下时按块换行（英文不拆单词）
        val cautionRaw = activity.getString(R.string.flash_warning_caution)
        val warnCaution = android.widget.TextView(activity).apply {
            text = cautionRaw.replace("|", "")
            textSize = 24f                       // 警示语：24sp
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF000000.toInt())
            setPadding(0, (4 * dp).toInt(), 0, 0)
        }
        warnCol.addView(warnTitle)
        warnCol.addView(warnDesc)
        warnCol.addView(warnCaution)
        warnRow.addView(warnIcon)
        warnRow.addView(warnCol)
        root.addView(warnRow)

        root.addView(android.widget.TextView(activity).apply {
            text = file.name
            textSize = 16f
            setTextColor(0xFF444444.toInt())
            setPadding(0, (10 * dp).toInt(), 0, 0)
        })

        // 内容包一层限高滚动容器：小分辨率/长文案时弹窗也不会被顶出屏幕
        val scroll = MaxHeightScrollView(activity).apply {
            maxHeightPx = (dm.heightPixels * 0.62f).toInt()
            isVerticalScrollBarEnabled = false
        }
        scroll.addView(root)

        // 标题已并入自定义视图，不再单独 setTitle，保证它也跟着闪
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(scroll)
            .setNegativeButton(R.string.flash_warning_cancel, null)
            .setPositiveButton(R.string.flash_warning_confirm) { _, _ -> onPicked(file) }
            .setCancelable(false)
            .show()

        val btnCancel = dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)
        val btnConfirm = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        btnCancel.setTextColor(0xFFD32F2F.toInt())   // 取消：红色
        btnConfirm.setTextColor(0xFF0076FF.toInt())  // 确认：蓝色

        // 布局完成后按可用宽度断行，保证「故障」这类词不被拆、不出现单字/单标点行
        TextLayout.wrapBlocks(warnTitle, titleRaw.split("|"))
        TextLayout.wrapBlocks(warnDesc, descBlocks)
        TextLayout.wrapBlocks(warnCaution, cautionRaw.split("|"))

        // ---- 警告区（图标 + 标题 + 文案）整体闪烁，每 500ms 切换一帧 ----
        var warnFrame = 0
        val warnBlink = object : Runnable {
            override fun run() {
                warnFrame = (warnFrame + 1) and 1
                if (warnFrame == 0) {
                    // 第 1 帧：红色警告标识 + 黑色字体（无底框）
                    warnIcon.setImageResource(R.drawable.flash_warning_icon_red)
                    warnTitle.setTextColor(0xFF000000.toInt())
                    warnDesc.setTextColor(0xFF000000.toInt())
                    warnCaution.setTextColor(0xFF000000.toInt())
                    warnRow.setBackgroundColor(0x00000000)
                } else {
                    // 第 2 帧：白色警告标识 + 红色底框 + 白色字体
                    warnIcon.setImageResource(R.drawable.flash_warning_icon_white)
                    warnTitle.setTextColor(0xFFFFFFFF.toInt())
                    warnDesc.setTextColor(0xFFFFFFFF.toInt())
                    warnCaution.setTextColor(0xFFFFFFFF.toInt())
                    warnRow.setBackgroundColor(0xFFD32F2F.toInt())
                }
                warnHandler.postDelayed(this, WARN_BLINK_INTERVAL_MS)
            }
        }
        warnHandler.postDelayed(warnBlink, WARN_BLINK_INTERVAL_MS)

        val tickHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var tick: Runnable? = null
        if (!bypass) {
            // 10 秒倒计时
            var left = 10
            btnConfirm.isEnabled = false
            btnConfirm.text = activity.getString(R.string.flash_warning_confirm_countdown, left)
            tick = object : Runnable {
                override fun run() {
                    left--
                    if (left <= 0) {
                        btnConfirm.isEnabled = true
                        btnConfirm.text = activity.getString(R.string.flash_warning_confirm)
                    } else {
                        btnConfirm.text =
                            activity.getString(R.string.flash_warning_confirm_countdown, left)
                        tickHandler.postDelayed(this, 1000)
                    }
                }
            }
            tickHandler.postDelayed(tick, 1000)
        }
        // 弹窗关闭后停掉闪烁 + 倒计时，避免空转（只设一次，避免后设的覆盖先设的）
        dialog.setOnDismissListener {
            warnHandler.removeCallbacks(warnBlink)
            tick?.let { tickHandler.removeCallbacks(it) }
        }
    }

    /** 手动选文件（SAF 文件选择器），作为目录读取的兜底 */
    private fun pickBySaf(activity: Activity) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        activity.startActivityForResult(intent, REQ_PICK_FILE)
    }

    /** 处理手动选文件结果（在 Activity.onActivityResult 中调用） */
    fun handleFilePickResult(activity: Activity, uri: Uri?) {
        if (uri == null) return
        Toast.makeText(
            activity,
            activity.getString(R.string.firmware_picked, uri.lastPathSegment ?: "?", 0, 0),
            Toast.LENGTH_SHORT
        ).show()
    }

    /** 设备端实现：总包数 = ceil(len / 128) */
    fun packetCount(size: Long): Int =
        ((size + PACK_SIZE - 1) / PACK_SIZE).toInt()
}
