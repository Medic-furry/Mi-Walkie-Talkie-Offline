# 开发文档（面向接手与二次开发）

> 本文说明本项目的**开发逻辑、协议细节、模块职责、扩展方式与已踩过的坑**。
> 目标：让一个没参与过开发的人，能看懂代码、改得动、知道哪些地方**绝对不能"优化"**。
> 上手顺序建议：先读第 2 节（项目由来）→ 第 3 节（架构）→ 第 5/6 节（协议）→ 第 13 节（怎么加功能）。
> **当前文档对应 V1.0.1**（versionCode 13）。版本号见 `app/build.gradle.kts`，本文不单独维护版本字段。

---

## 1. 这是什么

一个**完全离线、无需登录**的安卓 BLE 工具，用手机直连**小米对讲机**，
读写频道、读写设备参数、刷写固件。

> ⚠️ **机型范围：目前仅支持 2 代及更早机型** —— Shark（2 代）、Seal（1S）。
> 3 代及更晚机型未适配、未验证；新增机型需先抓 GATT 服务表与握手响应再自行适配（见第 14 节）。

- applicationId：`rex.medicfurry.miradio`（与既有应用共存，互不覆盖）
- 命名空间：`com.medicfurry.walkieoffline`
- minSdk 24 / targetSdk 34 / compileSdk 36，Kotlin + ViewBinding + 协程，Java 17

---

## 2. 项目由来：基于什么开发（重要）

> **基于 GitHub 用户 \*Darkhorse\* 的开源项目：** [Mi-Walkie-Talkie-Plus](https://github.com/Mi-Walkie-Talkie-by-Darkhorse/Mi-Walkie-Talkie-Plus)（分支 `2.13.7-plus`）

本项目是在上述社区开源项目基础上，用 **Kotlin 重写的独立离线实现**（不是修改版 APK，
也不是官方 SDK 封装）。项目**没有官方 SDK，也没有公开的官方协议文档**，
文中所有协议与交互细节来自以下工作：

1. **对设备通信行为的技术分析** —— 帧结构、命令码、protobuf 字段、交互时序
2. **真机验证** —— 在真实设备上逐项确认：GATT 服务表、握手响应、频道查询分页、刷机进度回包
3. **社区方案交叉参考** —— 升级流程等实现思路对照社区同类开源项目

> 因此代码里会出现"依据实测行为还原／勿改"的标记——那不是套话，
> 是指**这一段是设备端实际要求的行为**，包括一些看起来"不合理"的写法
> （如 MTU 要 163、两个通知通道都要订阅、500ms 延时）。
> **这些不要"优化"，改了就会连不上或升级失败**，详见第 15 节。

> 该说明中的技术分析、协议解释及部分实现说明由开发过程中使用的 AI 辅助生成。
> 由于协议分析与 Android / BLE 运行时行为存在复杂性，本文内容不保证绝对准确，
> 部分推断可能存在偏差，请自行验证。

第三方软件的名称、商标、原始代码、图标、文档及其他受保护内容，其权利归各自权利人所有。
本项目是**非官方第三方项目**，与相关软件开发者或权利人之间
不构成隶属、合作、授权、赞助或背书关系，也不保证与其所有版本持续兼容。

---

## 3. 整体架构

```
┌──────────────────────────────────────────────────────────┐
│ UI 层  (ui/)                                              │
│  DeviceScanActivity → MainActivity → ChannelSetting/Edit   │
│  FirmwareUpdateActivity / About / RiskDisclosure / Easter  │
│  （全部继承 BaseActivity：统一处理语言热切换）              │
├──────────────────────────────────────────────────────────┤
│ 会话层  BleConnector (ble/)                                │
│  GATT 连接、MTU、订阅、握手、命令队列、响应分发             │
│  对外暴露 StateFlow：state / channels / deviceParam / ...   │
├──────────────────────────────────────────────────────────┤
│ 协议层  protocol/                                          │
│  FrameCodec(帧编解码) ProtoIO(手写 protobuf wire)          │
│  SealProtos(消息模型) Crc16 FreqFormat CssTones            │
├──────────────────────────────────────────────────────────┤
│ 支撑  data/PrefsManager(偏好) debug/DebugLog(日志总线)      │
│       ConnectorHolder(跨页面持有 connector 单例)            │
└──────────────────────────────────────────────────────────┘
```

数据流向：UI 调 `BleConnector` 的方法下发命令 → 设备响应经 `FrameCodec.FrameParser` 切帧校验
→ 按 cmd 分发 → `SealProtos` 解码 → 更新 StateFlow → UI 收集刷新。
所有关键收发同时写入 `DebugLog`，实时显示在主页「调试终端」。

---

## 4. 目录与文件职责

```
app/src/main/java/com/medicfurry/walkieoffline/
├── MiWalkieApp.kt              应用入口，持有全局 PrefsManager
├── ble/
│   ├── BleScanner.kt           BLE 扫描，输出设备列表 Flow
│   ├── BleConnector.kt         【核心】GATT + 协议会话，969 行，注释最密
│   └── ConnectorHolder.kt      跨 Activity 共享 connector 实例
├── protocol/
│   ├── FrameCodec.kt           帧格式、命令码常量、流式解析器
│   ├── ProtoIO.kt              手写 protobuf varint / length-delimited
│   ├── SealProtos.kt           消息模型 + 常量（结果码、操作类型等）
│   ├── Crc16.kt                CRC16（帧校验）
│   ├── FreqFormat.kt           Hz ↔ "438.7125" 显示转换
│   └── CssTones.kt             亚音（模拟/数字正码/反码）列表
├── data/PrefsManager.kt        语言、首启标记、刷机倒计时开关等
├── debug/DebugLog.kt           全局日志总线（终端显示 / 复制日志的数据源）
└── ui/
    ├── BaseActivity.kt         【基类】语言热切换重建
    ├── FirstRunActivity.kt     首次启动：刷机风险 + 法律风险两步提示
    ├── PermissionActivity.kt   蓝牙/定位权限说明
    ├── DeviceScanActivity.kt   设备扫描选择
    ├── MainActivity.kt         主页：参数、频道入口、调试终端、复制日志、彩蛋、
    │                           新增「恢复连接」入口（断线重连，见第 12 节）
    ├── ChannelSettingActivity.kt  频道列表 + 拖拽排序 + 批量整理 + 预设频段页，
    │                           2835 行，V1.0.1 改动量最大的文件
    ├── ChannelEditActivity.kt     频道编辑；预设频道只读
    │                           （名称/频道号/频率不可改，仅亚音可改）
    ├── FirmwareManager.kt         固件目录扫描、选文件、启动升级
    ├── FirmwareUpdateActivity.kt  刷机进度页（状态机）
    ├── AboutActivity.kt           关于：版本、GitHub、风险告知、语言切换
    ├── RiskDisclosureActivity.kt  风险告知 + 软件声明（HTML 渲染）
    ├── EasterEggActivity.kt       彩蛋页（可缩放大图）
    ├── ZoomableImageView.kt       自定义双指缩放 ImageView
    ├── TextLayout.kt              按宽度断行、不拆词的排版
    └── MaxHeightScrollView.kt     限高滚动容器
```

**资源目录（`app/src/main/res/`）中与频道交互相关的 drawable：**

```
drawable/
├── bg_fab_group.xml         底部「整理 | +」合并按钮组的圆角方形容器背景，
│                            实色 #28AA9A（主题青绿）、圆角 14dp
├── bg_slot_drop_target.xml  拖拽有效落点高亮框：填充 #3328AA9A + 2dp #28AA9A 描边
├── bg_slot_placeholder.xml  拖拽中的空槽占位背景：1dp #B0BEC5 虚线框
├── bg_channel_card.xml      自定义频道行卡片背景
├── bg_channel_header.xml    频道页头部背景
├── bg_edit_pill.xml         编辑页胶囊输入框背景
└── bg_preset_item.xml       预设频道格子背景
```

> ⚠️ **颜色口径（两类并存，是用户明确要求，别去"统一"）**：
> - **主题青绿 `#28AA9A` 是本项目 UI 主色**：起拖扩散涟漪渐变、`bg_slot_drop_target` 的描边、
>   勾选行底色 `0x3328AA9A`、插入指示框文字/描边、**当前频道行 RX/TX 文字高亮**
>   （非当前行为黑色 `0xFF000000`）——全部取这一个色值。
> - **设备端原版蓝 `#0076FF` 只保留在"确认性质"的文字按钮上**：频道编辑页频率弹窗的「确定」
>   （`ChannelEditActivity.kt:229`）、固件管理的「确认」（`FirmwareManager.kt:396`）。
>   这两处是**全 `ui/` 目录仅存**的 `0xFF0076FF`，与频道列表行高亮**无关**，
>   不要因为它们就把列表行高亮也"改回"蓝色。

---

## 5. BLE 连接与握手时序

**必须严格按此顺序**（设备端实测要求，已在真机上验证）：

1. `connectGatt` → 连接成功
2. `requestConnectionPriority(HIGH)` → `requestMtu(163)` ← **是 163，不是 247**
3. `onMtuChanged` → **延迟 500ms** → `discoverServices`
4. 按服务识别型号：`0000fdab-...` = Shark(deviceType 4)；`0000aee7-...` = Seal(deviceType 5)
5. 依次订阅通知通道 `aec8` → `feab`（写 CCCD `2902`），**两个都订阅完才发握手**
6. `feab` 订阅完成 → `requestConnectionPriority(BALANCED)` → 发送握手帧 `0x4E21`
7. 设备回 `ALLOW`(4) → 依次下发：参数查询 `0x4E23` → 信道状态查询 `0x4E26` → 信道查询 `0x4E29`

GATT 特征（UUID 后缀统一为 `-0000-1000-8000-00805f9b34fb`）：

| UUID | 方向 | 作用 |
|------|------|------|
| `0000aec7-...` | app → 设备 | 写命令通道 |
| `0000aec8-...` | 设备 → app | 主通知通道 |
| `0000feab-...` | 设备 → app | 第二通知通道（fdab 服务上，同样需要订阅） |
| `00002902-...` | — | CCCD，订阅用 |

> ⚠️ **坑 1**：`onCharacteristicChanged` 有 3 参（API 33+）和 2 参（API ≤32）两个签名。
> 只重写 3 参版本，在 Android 11（API 30）等老机器上**永远收不到通知**，表现是"设备无响应"。
> 代码中两个都重写了，别删掉旧的。

---

## 6. 帧格式与命令码

### 6.1 帧结构（`FrameCodec`）

```
[0]     0xFE
[1]     0x01
[2..3]  totalLen = 8 + dataLen + 2     大端
[4..5]  cmd 命令码                      大端   ← 不是 transMode！
[6..7]  serial（0..0xFFFE 递增）         大端
[8..]   protobuf payload
末尾+2   CRC16（覆盖前面所有字节）       大端 [hi, lo]
```

### 6.2 命令码

| cmd | 含义 | 响应 |
|-----|------|------|
| 0x4E21 | Connect 握手 | 0x4E22（**+1**） |
| 0x4E23 | DeviceParam 查询 | 0x4E25（**+2**） |
| 0x4E24 | DeviceParam 设置 | 0x4E25 |
| 0x4E26 | ChannelState 查询 | 0x4E28（**+2**） |
| 0x4E27 | ChannelState 设置（双频开关） | 0x4E28 |
| 0x4E29 | ChannelInfo 查询（读频道列表） | 0x4E2B（**+2**） |
| 0x4E2A | ChannelInfo 增删改 | 0x4E2B |
| 0x4E30 | LocationInfo（本项目裁剪） | — |
| 0x4E2C | 升级控制（START/COMPLE/REBOOT） | 0x4E2E |
| 0x4E2D | 升级数据包（128 字节/包） | 0x4E2E |
| 0x4E2E | 设备升级响应（result + 已确认 offset） | — |

> ⚠️ **坑 2**：响应码规律不统一。**信道类的 0x4E29/0x4E2A 响应都是 0x4E2B**，
> 语义靠"最近一条发出的信道命令"区分（查询 = 流式列表，增删改 = result 确认）。
> 代码里用 FIFO 期望队列 + cmd 配对处理，不要改成只按响应码判断。
> 更细的 ACK 语义澄清见 8.1。

---

## 7. protobuf 消息（`SealProtos` / `ProtoIO`）

不引入 protobuf 运行库，手写 wire format（varint + length-delimited），保持 APK 轻量。

**ChannelInfo 字段**（编码见 `encodeChannelInfo`）：

| field | 含义 | 备注 |
|-------|------|------|
| 1 | no 信道号 | |
| 2 | type 类型 | 0=PUBLIC 1=REMOTE 2=USERDEF |
| 3 | txPower | |
| 4 | rxFreq 接收频率 | 单位 Hz |
| 5 | txFreq 发射频率 | 单位 Hz |
| 6 | name 信道名 | **GB2312** 编码 |
| 7 | sq 静噪 | |
| 8 | vox | |
| 9 | rxCss 接收亚音 | 0=关闭 |
| 10 | txCss 发射亚音 | 0=关闭 |

**DeviceParam 字段**：version / result / verHw / verSoft / deviceName(GB2312) / bleName(UTF-8) /
charge / txPower / timeDisplay / voiceType / voiceAlert / pwSaveMode。
`verHw` 用于刷机时按 `shark_*_hw<N>.bin` 过滤固件。

关键枚举：

| 常量组 | 取值 |
|--------|------|
| Connect result | DISCONNECT=0 CONNECT=1 ALLOW=4 REFUSE=5 WAIT=6 LOWPOWER=7 BUSY=12 |
| ChannelOperate | INSERT=0 DELETE=1 MODIFY=2 QUERY=3 |
| ChannelType | PUBLIC=0 REMOTE=1 USERDEF=2 |
| Channel result | OK=0 ERROR=1 EMPTY=2 FORBIDE=3 **QUERY_CONTINUE=4 QUERY_FINISH=5** |
| Upgrade | START=1 COMPLE=2 REBOOT=3 |

> ⚠️ **坑 3**：`uint32` 字段必须按**无符号 32 位**编码（不符号扩展）。
> 若按有符号 long 编码（10 字节），设备解析失败——实测 CRC32 字段符号扩展后，
> 设备对 COMPLE 命令完全不回 ACK，最终 60s 超时。见 `ProtoIO.writeUInt32Field`。

---

## 8. 频道操作流程

**查询（0x4E29）是链式的**（设备端行为如此，已实测确认）：
先显示本地缓存列表，再发 `QUERY + chType=USERDEF(2)`；
每收到一帧 `result=QUERY_CONTINUE(4)` 的频道，先入**临时缓冲区**，立即发裸 QUERY 续页；
遇到 `EMPTY(2)` / `QUERY_FINISH(5)` / 10 秒兜底时，**一次性**替换列表（避免逐帧刷新导致闪烁）。

### 8.0 两个 Tab

| Tab | 数据来源 | 能否增删改 |
|-----|---------|-----------|
| 自定义频道 | 设备 0x4E29 查询结果（chType=USERDEF） | 可增删改、可拖拽排序、可批量整理 |
| 预设频道 | 设备端固定数组（见 `buildPresetChannels`） | **编号固定，App 不增删** |

- 预设频道是**设备端固定数组**：公众 20 个（`seal_public_all_channel`，409.7500 起 12.5kHz 步进，
  chType=PUBLIC）、远距离 20 个（`seal_remote_channel`，430.1375/430.4375 成对按 1MHz 步进，
  chType=REMOTE）。这些数据来自设备端资源（aapt2 dump 核对），不是 App 编的
- 单击预设频道 = 跳台激活（0x4E2A + MODIFY）
- 长按预设频道 = 打开**只读**编辑页：名称 / 频道号 / 收发频率锁死，**仅亚音可编辑**
  （`ChannelEditActivity.isPreset`，L83-119）

**自定义频道的交互**（与预设频道同构，但可增删改）：

- **单击 = 跳台激活**：`0x4E2A` + MODIFY，写入本页绑定的槽位——
  `isMajor=true` → setCh1，否则 setCh2（设备端 `g.a(channel, isMajor, ST_UPDATE)`）
- **长按 = 删除 / 编辑菜单**，**删除有二次确认对话框**
- **设备模式保护**：设备处于**扫描模式（deviceMode 3/4）或组队模式（5/6）时拒绝写频道**
  并 Toast 提示（`scan_mode_can_not_setting` / `team_mode_can_not_setting`）。
  排序前的激活槽预切换同样受此约束（这两种模式下不发临时切换）
- 整理模式下单击 / 长按被屏蔽（`if (!organizeMode)` 守卫），点击只用于勾选

### 8.1 ACK 语义澄清（0x4E2B 一身兼两职）

这是 V1.0.1 修删除同步 bug 时确立的核心认知，写代码前必须看懂。代码在
`ble/BleConnector.kt#handleChannel`（L432）。

**帧类型判定 —— `pendingCmds` FIFO 期望队列只负责这一件事：**

- **0x4E2B 同时承载两种语义**：① 0x4E29 查询链的响应（流式列表）② 0x4E2A 增删改的确认帧。
  判定"这条 0x4E2B 是哪一种"靠 **FIFO 期望队列 `pendingCmds`**（下发时记录期望的响应），
  **不要只看响应码**。查询帧的判据是 `!expectModify && (op.option == QUERY || op.option < 0)`（L436-437）

**确认帧内部 —— 只分 DELETE 与 非 DELETE 两条路：**

- 确认帧分支（L467-475）：
  ```kotlin
  val opType = if (op.option >= 0) op.option else pendingChannelOp
  if (op.result == ChResult.OK) {
      when (opType) {
          DELETE -> removeCustomChannel(ch?.no?.takeIf { it >= 0 } ?: pendingDeleteNo)
          else   -> ch?.takeIf { it.isValid }?.let { addCustomChannel(it) }   // upsert
      }
  }
  ```
- `opType` **优先取回显的 `option`**；`option` 缺省（解码为 `-1`，部分固件确认帧不回带）时，
  用 `pendingChannelOp`（`sendChannelOp` 下发时记录的操作类型，L879-885）兜底
- DELETE 的 `ch`/`no` 回显也可能缺，用 `pendingDeleteNo` 兜底——
  **这两个兜底是"删除确认后列表必然同步移除"的关键**，别当成冗余删掉
- INSERT / MODIFY 一律走 `else` → `addCustomChannel`，它是 **upsert**
  （`no` 已存在则替换，不存在则追加并按 `no` 排序，L528-540）

**设备回显 option 的怪癖（不要拿它当操作类型的唯一真相）：**

- 删除确认帧回显 `option=1(DELETE)`，但**不回显 ch**（16B 短帧）
- **新增（INSERT）会被设备回显成 `option=2(MODIFY)`** —— 回显的操作类型与实际下发的不一致
- 被拒的操作回显 `option=0`
- 当前实现之所以安全，正是因为分支只判 **DELETE / 非 DELETE**，不依赖 INSERT 与 MODIFY 的区分。
  **将来若有人改成按 option 三分支处理，必须先真机验证 INSERT 场景**

**旧 Bug 根因（V1.0.1 已修）：** 旧代码仅凭 `option` 二次判定操作类型；删除确认帧
`option` 缺省（`-1`）时误入查询分支 → 本地列表不移除条目 → UI 不同步 →
用户以为没删成功而重复点击 → 把相邻频道一并删掉（真机反馈 Bug）。

**另外两条相关约束：**

- **增删改成功后靠回显的 ch 原位更新列表，不重新查询**。重新查询会清空刚写入的频道
  （已知坑，见第 15 节坑 14）
- **删除确认帧不回显 ch**，修改 / 新增确认帧才回显；所以删除的列表同步依赖
  `removeCustomChannel` + `pendingDeleteNo` 兜底，不能指望确认帧给内容

### 8.2 拖拽与批量整理

- **长按防误碰**：普通（非整理）模式下，按住列表行把手约 `DRAG_HOLD_MS`（**1 秒**）才开始起拖；
  等待期间把手有 1.35 倍缓慢放大动画作为反馈，抬手（UP/CANCEL）或手指滑动超过
  `DRAG_HOLD_CANCEL_SLOP_DP`（24dp）即取消本次起拖。
  **整理（批量）模式下不需要防误碰，按下立即起拖**（勾选走复选框/行本体，把手上再等 1 秒纯属拖累）
- **拖拽实现**：`ItemTouchHelper`。拖拽期间列表**不换位**（`onMove` 恒返回 false），
  真正的换位推迟到松手后按落点一次性完成
- **相邻行让位（peek 缓冲）**：落点为占用卡时，让位区间内的行整体平移一个行高腾出缺口；
  起拖位的**上下相邻行**各再往外撑开 `DRAG_PEEK_DP`（12dp，真机实测 ≈33px @440dpi），
  视觉上"卡片被拎起来、两边让一让"。区间内的行**不叠** peek——叠了会把缺口填回去，
  落点判定与缺口高度都会失真
- **落点视觉**：空槽落点高亮框 `bg_slot_drop_target`（青绿描边）+ 空槽占位 `bg_slot_placeholder`
  （灰色虚线）；缺口中间还有一枚**青绿数字框**（文字/描边均 `#28AA9A`）指示落子后的槽位号
- **起拖揭示动画**：从**按压点**（把手中心，用 `getLocationInWindow()` 对把手与卡片
  双取相减得到卡片内相对坐标）扩散一枚圆形揭示涟漪，`DRAG_REVEAL_MS`（260ms）内
  扩到覆盖整张卡片（半径上限 = 卡片对角线 × 1.05），圆内是主题青绿 `#28AA9A` 的
  `RadialGradient` 渐变、**无边框描边**，扩满后淡出；同时被拖卡片 alpha 由 1 插值到 `DRAG_ALPHA`
- **成组拖拽**：整理模式下勾选多个频道后起拖，所有被选中的**真实卡片**以相同透明度跟随手指
  移动（用真实 ViewHolder 的 `translationX/Y/Z` 平移，不画假卡片、不套框）；
  间距 `step = if (余量 > 0) minOf(110dp, 余量 / 组员数) else 56dp`
  （`updateGroupFollowerPositions`）——**有余量时取 110dp 与余量均值中的较小者，可能低于 56dp；
  只有余量 ≤ 0 才兜底 56dp**。代码注释里"最低 56px"的表述与实际不符，以代码为准
- **「共 N 项」徽标**：组上方悬停显示，定位 = **卡顶 + inset 起算**；
  按"bottom = 卡顶 + inset"写会在角标高度大于 inset 时伸出卡片上沿、压到页签栏——真机实测过
- **拖拽期间禁止 notify**：`notifyDataSetChanged` / `notifyItemMoved` 会触发
  `assertNotInLayoutOrScroll` 崩溃，调用时机见第 15 节坑 17
- 当前频道行 RX/TX 文字用主题青绿 `#28AA9A` 高亮（非当前行为黑色 `0xFF000000`）
- 排序执行中（`reorderActive`）屏蔽再次拖拽与其它增删改，进度对话框模态展示

### 8.3 排序落盘：ch.no 重分配 + DELETE/INSERT 原语

**排序 = 重新分配 `ch.no`**（`no` 即列表顺序，设备按 `no` 升序返回频道）。

以下四条是设备端实测确立的行为，**不要试图"优化"**：

1. `ch.no` 由 App 决定、设备照单存，范围 `0..19` 共 20 槽（`MAX_CHANNEL_SLOTS = 20`）。
   超出范围（20 / 30 / 50）一律 `result=1` 拒绝，**即使有空闲槽**
2. `no=0` 可以正常写回，**不是**设备限制（曾以为 0 号槽不可写，实测可以）
3. **移动原语只能是 DELETE(old) + INSERT(new)**：MODIFY 改 `no` 是「复制」不是「移动」
   ——改完旧条目还在，同内容两份并存，且 ACK 回显新号看不出异常
4. **INSERT 到已占用槽 = 静默覆盖销毁**（ACK `result=0`，原频道无声消失、无任何警告）。
   INSERT / MODIFY 都是盲写 upsert，所以排序前必须先删目标槽或确保空闲，UI 必须拦截占用冲突

整理模式（批量）落地的算法（`buildOrganizePlan`）：先把所有「非原地保留」的槽
DELETE 腾空，再按槽位升序 INSERT——这样每一步 INSERT 的目标槽必然已被上一步腾空，
天然规避第 4 条。

### 8.4 性能事实（排序相关）

| 操作 | 往返耗时 |
|------|---------|
| DELETE | 119–165ms（≈146ms） |
| INSERT | 207–258ms（≈233ms） |
| 单频道移动（1 删 + 1 插） | ≈380ms |

- 写操作**全局串行**，同一时刻只能有一条在飞；激活槽切换也要逐条间隔 250ms 下发
  （BLE 写入在上一笔 `onCharacteristicWrite` 落定前会被 GATT 拒绝，连发会静默丢帧）
- 批量写入期间 BLE **曾中断过一次**（`status=19` 对端终止），因此批量排序需要考虑
  断线恢复 / 回滚（当前实现：保留剩余队列供「重试剩余」，见第 15 节坑 18）

---

## 9. 频道显示口径

这一节是"序号到底显示什么"的完整结论，**改动任何序号/编号显示前先读这里**：

- App 列表序号 = **`ch.no + 1`（设备槽位号）**，**不是**列表位置 `position + 1`
- 对讲机屏幕按槽位号显示，且**空号占行**（例如屏幕显示 16 / 17空 / 18 / 19空 / 20），
  **App 必须与屏幕逐项一致**
- 曾经用 `position + 1` 导致把空号抹平（20 个槽压成 18 行），App 与设备屏幕对不上
- **判定显示口径的唯一权威是设备屏幕，不能凭代码假设**——必须真机比对
- `ChannelEditActivity` 的频道号 Spinner：**显示用 `(it + 1)`，内部 entries 仍是 0 基 `no`**；
  可选范围文案为「1–20」
- 两种显示口径在 `ChannelAdapter` 里由 `numberByPosition` 切换：
  `true` = 按行位置显示（整理模式拖动中就地预览新槽位号），
  `false` = 按设备信道号 `ch.no` 显示（压缩列表 / 20 槽位视图下的频道行）

---

## 10. 固件刷写流程

1. 读取固件文件（默认目录 `/sdcard/Mi-walkie-talkie.firmware/`，或手动选择）
2. 按硬件版本过滤：`verHw` → 匹配 `shark_*_hw<N>.bin`
3. 发 `0x4E2C` + **START=1**（携带总字节、总包数）
4. 按 **128 字节/包** 发 `0x4E2D`（`PACK_SIZE = 128`，与协商 MTU 无关，是设备端固定要求）
5. 设备每包回 `0x4E2E`（含 result + 已确认 offset），进度 = 已确认字节 / 总字节
6. 发完发 `0x4E2C` + **COMPLE=2**（携带 CRC32）→ 等待设备校验
7. 状态机：x=1 传输中 / x=5 成功 / x=6 失败 / x=7 低电量失败
8. 成功后提示对讲机将重启

- Android 11+ 读取目录需「所有文件访问权限」，代码会引导去设置页
- 刷机过程**禁止返回**（只弹"正在升级固件，请稍候…"）
- 关于页隐藏开关（连点版本号 5 次）可关闭刷机确认的 10 秒倒计时

---

## 11. 状态与数据流

- 连接状态、频道列表、设备参数、双频状态、设备模式均用 **StateFlow** 暴露，
  页面重建 / 从子页返回后不会错位（这是刻意设计，别改成普通回调）
- 调试日志走 `DebugLog`（全局总线，最多 300 行，带时间戳）
  - 主页终端 **150ms 节流**批量刷新（避免高频日志卡顿，但日志一行不少）
  - 终端下「复制日志」按钮把全部日志写入剪贴板
  - ⚠️ 终端 TextView 开了 `textIsSelectable`，**会吃掉点击事件**——
    所以彩蛋的点击监听挂在「调试终端」标题上，不是终端窗口
- 排序执行期间 `customChannels` 的回显**被抑制刷新**（`reorderActive` 时不重排列表），
  保持用户拖拽后的排列直到进度框结束，finish / abort 时统一同步

---

## 12. 页面与导航

```
启动 → FirstRunActivity(仅首次) → PermissionActivity → DeviceScanActivity → MainActivity
MainActivity ├→ ChannelSettingActivity ├→ ChannelEditActivity
             ├→ FirmwareUpdateActivity（经 FirmwareManager 选文件）
             └→ AboutActivity ├→ RiskDisclosureActivity（风险告知 + 软件声明）
                              └→ EasterEggActivity（彩蛋：主页「调试终端」标题连点 5 次）
```

- 语言切换：中文 ⇄ English，改 `PrefsManager.language` 后 `recreate()`；
  `BaseActivity.onResume` 检测到语言变化会自动重建其它页面，**无需重启 App**
- 语言切换按钮文案刻意**反向显示**：中文模式显示 "Language"，英文模式显示 "语言"（便于误入外语的用户切回）
- **「恢复连接」入口**：MainActivity 新增，位于「断开连接」**上方**；扫描页带进来的对讲机
  断线后用它重连。**仅在 BLE 处于 DISCONNECTED 时可用**——连接正常时置灰
  （`isEnabled=false` + `alpha=0.35`），点击时还会二次校验状态

---

## 13. 二次开发指引

### 13.1 新增一条协议命令
1. 在 `FrameCodec` 加 `const val CMD_XXX = 0x....`
2. 在 `SealProtos` 加消息模型（字段号来自实测通信分析），实现 encode/decode
3. 在 `BleConnector` 加发送方法：编码 → `FrameCodec.encode(cmd, payload)` → 写 `aec7`
4. 若响应码不是现有规律，在 `handleFrames` 的分发里补一个分支（并同步更新期望队列逻辑）
5. 用调试终端看收发，确认设备回包

### 13.2 新增一个主页设置项
1. `activity_main.xml` 里照 `RadioSettingItem` 风格加一组（标题 + 灰字描述）
2. `strings.xml` 与 `values-en/strings.xml` **同时加中英文文案**
3. 在 `MainActivity.setupSettingItems()` 绑点击，调 `BleConnector` 下发

### 13.3 新增页面
1. 建 `XxxActivity : BaseActivity()`（继承才能享受语言热切换）
2. 建 `activity_xxx.xml`（ViewBinding 自动生成 `ActivityXxxBinding`）
3. 在 `AndroidManifest.xml` 注册（`android:exported="false"`）

### 13.4 改文案 / 加图片
- 文案改 `res/values/strings.xml`（中文）和 `res/values-en/strings.xml`（英文），**两边一起改**
- 法律类文本（如软件声明）标了 `translatable="false"`，不随语言翻译
- 图片放 `res/drawable-nodpi/`（常规图标）；**需要保真的大图放 `assets/`**，
  用 `BitmapFactory` + `ARGB_8888` + `inScaled=false` 解码（参见彩蛋页做法）

---

## 14. 调试方法

| 手段 | 用途 |
|------|------|
| 主页「调试终端」 | 实时看协议收发、状态机、错误（不上弹窗） |
| 「复制日志」按钮 | 一键复制全部日志，便于贴 issue / 发群里求助 |
| `adb logcat` | 看 `DebugLog` 同时写入的 logcat 输出 |
| 关于页连点版本号 5 次 | 切换"刷机确认 10 秒倒计时"开关 |
| 主页「调试终端」标题连点 5 次 | 打开彩蛋页（验证手势/页面跳转是否正常） |
| `onServicesDiscovered` 会打印完整 GATT 表 | 换设备/换型号时先拿这张表 |
| `ChannelSettingActivity` 的排序日志 | 拖拽落点、换位映射、迁移步骤条数（`finishSlotDrag` / `executeMovePlan`） |

---

## 15. 已踩过的坑（**不要"优化"这些**）

1. **MTU 要 163**，不是 247；连接后先 HIGH 优先级，订阅完成切回 BALANCED，MTU 后延迟 500ms 再发现服务。
2. **两个通知通道（aec8 + feab）都要订阅完**才发握手，顺序不能变。
3. **API ≤32 的 2 参 `onCharacteristicChanged` 必须保留**，否则老机器收不到通知。
4. **帧头 [4..5] 是 cmd 不是 transMode**；`deviceType` 只在 app 侧判断是否走文件传输分支，不进帧。
5. **uint32 不能符号扩展**（否则刷机 COMPLE 不回 ACK，60s 超时）。
6. **信道响应 0x4E2B 复用**，靠 FIFO 期望队列区分语义，不能只看响应码（详见 8.1）。
7. **CRC16 大端**追加在帧尾，覆盖头 + 数据。
8. 刷机分包固定 **128 字节**，与协商 MTU 无关。
9. 终端 TextView 可长按复制，**会吞掉点击**——点击类监听要挂别处。
10. 首页列表链式查询的**临时缓冲区 + 一次性替换**是刻意设计（避免闪烁/错序），别改成逐帧刷新。
11. **MODIFY 改号 = 复制，不是移动**。任何「移动 / 排序 / 交换」都不能走 MODIFY 改 `no`，
    必须 DELETE(old) + INSERT(new)。改完旧条目还在、同内容两份并存，且 ACK 回显新号
    看不出异常——这个 bug 在真机上极难察觉。
12. **INSERT 到已占用槽 = 静默覆盖销毁**（ACK `result=0`，原频道无声消失无警告）。
    INSERT / MODIFY 都是盲写 upsert，所以改号 / 排序 / 交换前必须先删目标槽或确保空闲，
    **UI 必须拦截占用冲突**。
13. **ACK 的 `option` 字段有怪癖，不能拿它当操作类型的唯一真相**：真机实测删除确认帧回显
    `option=1(DELETE)` 但**不回显 ch**（16B 短帧）；**新增（INSERT）会被设备回显成
    `option=2(MODIFY)`**；被拒的操作回显 `option=0`；部分固件的确认帧干脆不回带 option
    （解码为 `-1`）。当前实现之所以安全，是因为确认帧分支**只判 DELETE / 非 DELETE**：
    `opType = if (op.option >= 0) op.option else pendingChannelOp`，option 缺省时用下发时记录的
    操作类型兜底，DELETE 的 no 缺显时用 `pendingDeleteNo` 兜底。
    **不要改成按 option 三分支（INSERT/MODIFY/DELETE）处理**——那会在 INSERT 场景踩回显错乱的坑，
    真要改必须先真机验证 INSERT。详见 8.1。
14. **增删改后靠 0x4E2B 回显的 ch 原位更新列表，不要重新查询**。重新查询会清空刚写入的
    频道（已知坑，曾表现为"刚删的频道又回来了 / 刚加的频道不见了"）。
15. **删除确认帧（0x4E2B，16B）不回显 ch**，修改 / 新增确认帧才回显。删除后的列表同步依赖
    `removeCustomChannel(回显no ?: pendingDeleteNo)` 的兜底，不能指望确认帧给内容。
16. **序号显示口径是设备槽位号（`no + 1`），不是列表位置**。涉及序号 / 编号显示必须与
    设备屏幕逐项比对（对讲机屏幕空号占行），不能凭代码假设。详见第 9 节。
17. **拖拽排序中 `notifyDataSetChanged` / `notifyItemMoved` 会触发
    `assertNotInLayoutOrScroll` 抛 `IllegalStateException`**（真机实测崩溃）。拖拽期间
    的视觉状态（translation / alpha / 高亮）必须直接改视图；数据更新要么等松手后
    （`clearView` 里已 `post` 到布局结束），要么走单行 `notifyItemChanged`。
18. **批量写入期间 BLE 可能中断**（`status=19` 对端终止），批量操作要考虑断线恢复 / 回滚。
    当前实现保留剩余队列供「重试剩余」，但**没有自动回滚**——失败后设备停在中间态，
    以设备实际状态为准。
19. **`ItemTouchHelper` 的 `clearView` / `onSelectedChanged(ACTION_STATE_IDLE)` 与
    `RecoverAnimation` 的交互**：拖拽视觉状态（translation / alpha / translationZ / 让位偏移 /
    起拖扩散动画）必须在 `clearView` 与 `resetDragVisuals` 里**统一复位**，否则 ViewHolder
    复用时残留。注意 `onSelectedChanged` 在 `select()` 末尾无条件回调（含拖完走
    RecoverAnimation 那条分支），`clearView` 在回收路径上可能不触发或延时触发——
    所以复位不能只依赖 `clearView`。

---

## 16. 已知限制

- **仅覆盖 2 代及更早机型**（Shark 2 代、Seal 1S）；3 代及更晚机型、其它型号需先抓 GATT 表与握手响应自行适配
- 部分能力（如位置共享 0x4E30）未实现
- protobuf 为手写实现，字段号来自实测通信分析，未覆盖协议全部字段
- 刷写固件有变砖风险，请自行承担（详见 README 与 App 内风险告知）
- 批量整理失败后**没有自动回滚**：设备可能停在中间态，需用户退出页面重进查看实际状态
  （见第 15 节坑 18）

---

## 17. 维护约定

- 协议相关改动**必须真机验证**，不能只凭推理
- 涉及"依据实测行为还原／勿改"的逻辑，改动前必须真机验证，不要凭推理修改
- 所有 UI 文案中英文双语同步；法律文本不翻译
- 新增/修改协议代码时，同步更新本文档第 6/7 节的表格
- **凡涉及「序号 / 编号」显示，判定口径的唯一权威是对讲机屏幕，必须真机比对，不能凭代码假设**
- **拖拽排序涉及写设备（DELETE + INSERT），任何改动必须真机验证**，并注意 20 槽上限与占用冲突
- 改动拖拽视觉时，同步检查 `clearView` / `resetDragVisuals` 两条复位路径（见第 15 节坑 19）
