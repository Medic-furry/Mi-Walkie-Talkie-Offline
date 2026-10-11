# 开发文档（面向接手与二次开发）

> 本文说明本项目的**开发逻辑、协议细节、模块职责、扩展方式与已踩过的坑**。
> 目标：让一个没参与过开发的人，能看懂代码、改得动、知道哪些地方**绝对不能"优化"**。
> 上手顺序建议：先读第 2 节（项目由来）→ 第 3 节（架构）→ 第 5/6 节（协议）→ 第 12 节（怎么加功能）。

---

## 1. 这是什么

一个**完全离线、无需登录**的安卓 BLE 工具，用手机直连**小米对讲机**，
读写频道、读写设备参数、刷写固件。

> ⚠️ **机型范围：目前仅支持 2 代及更早机型** —— Shark（2 代）、Seal（1S）。
> 3 代及更晚机型未适配、未验证；新增机型需先抓 GATT 服务表与握手响应再自行适配（见第 13 节）。

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
> **这些不要"优化"，改了就会连不上或升级失败**，详见第 14 节。

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
│   ├── BleConnector.kt         【核心】GATT + 协议会话，801 行，注释最密
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
    ├── MainActivity.kt         主页：参数、频道入口、调试终端、复制日志、彩蛋
    ├── ChannelSettingActivity.kt  频道列表（自定义/预设）
    ├── ChannelEditActivity.kt     频道编辑
    ├── FirmwareManager.kt         固件目录扫描、选文件、启动升级
    ├── FirmwareUpdateActivity.kt  刷机进度页（状态机）
    ├── AboutActivity.kt           关于：版本、GitHub、风险告知、语言切换
    ├── RiskDisclosureActivity.kt  风险告知 + 软件声明（HTML 渲染）
    ├── EasterEggActivity.kt       彩蛋页（可缩放大图）
    ├── ZoomableImageView.kt       自定义双指缩放 ImageView
    ├── TextLayout.kt              按宽度断行、不拆词的排版
    └── MaxHeightScrollView.kt     限高滚动容器
```

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

- 单击自定义频道 = **跳台激活**（`0x4E2A` + MODIFY，isMajor=true）
- 设备处于扫描模式（deviceMode 3/4）或组队模式（5/6）时拒绝写频道，提示用户
- 长按 = 删除 / 编辑菜单，删除有二次确认
- 当前频道行 RX/TX 文字用蓝色 `0xFF0076FF` 高亮

---

## 9. 固件刷写流程

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

## 10. 状态与数据流

- 连接状态、频道列表、设备参数、双频状态、设备模式均用 **StateFlow** 暴露，
  页面重建 / 从子页返回后不会错位（这是刻意设计，别改成普通回调）
- 调试日志走 `DebugLog`（全局总线，最多 300 行，带时间戳）
  - 主页终端 **150ms 节流**批量刷新（避免高频日志卡顿，但日志一行不少）
  - 终端下「复制日志」按钮把全部日志写入剪贴板
  - ⚠️ 终端 TextView 开了 `textIsSelectable`，**会吃掉点击事件**——
    所以彩蛋的点击监听挂在「调试终端」标题上，不是终端窗口

---

## 11. 页面与导航

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

---

## 12. 二次开发指引

### 12.1 新增一条协议命令
1. 在 `FrameCodec` 加 `const val CMD_XXX = 0x....`
2. 在 `SealProtos` 加消息模型（字段号来自实测通信分析），实现 encode/decode
3. 在 `BleConnector` 加发送方法：编码 → `FrameCodec.encode(cmd, payload)` → 写 `aec7`
4. 若响应码不是现有规律，在 `handleFrames` 的分发里补一个分支（并同步更新期望队列逻辑）
5. 用调试终端看收发，确认设备回包

### 12.2 新增一个主页设置项
1. `activity_main.xml` 里照 `RadioSettingItem` 风格加一组（标题 + 灰字描述）
2. `strings.xml` 与 `values-en/strings.xml` **同时加中英文文案**
3. 在 `MainActivity.setupSettingItems()` 绑点击，调 `BleConnector` 下发

### 12.3 新增页面
1. 建 `XxxActivity : BaseActivity()`（继承才能享受语言热切换）
2. 建 `activity_xxx.xml`（ViewBinding 自动生成 `ActivityXxxBinding`）
3. 在 `AndroidManifest.xml` 注册（`android:exported="false"`）

### 12.4 改文案 / 加图片
- 文案改 `res/values/strings.xml`（中文）和 `res/values-en/strings.xml`（英文），**两边一起改**
- 法律类文本（如软件声明）标了 `translatable="false"`，不随语言翻译
- 图片放 `res/drawable-nodpi/`（常规图标）；**需要保真的大图放 `assets/`**，
  用 `BitmapFactory` + `ARGB_8888` + `inScaled=false` 解码（参见彩蛋页做法）

---

## 13. 调试方法

| 手段 | 用途 |
|------|------|
| 主页「调试终端」 | 实时看协议收发、状态机、错误（不上弹窗） |
| 「复制日志」按钮 | 一键复制全部日志，便于贴 issue / 发群里求助 |
| `adb logcat` | 看 `DebugLog` 同时写入的 logcat 输出 |
| 关于页连点版本号 5 次 | 切换"刷机确认 10 秒倒计时"开关 |
| 主页「调试终端」标题连点 5 次 | 打开彩蛋页（验证手势/页面跳转是否正常） |
| `onServicesDiscovered` 会打印完整 GATT 表 | 换设备/换型号时先拿这张表 |

---

## 14. 已踩过的坑（**不要"优化"这些**）

1. **MTU 要 163**，不是 247；连接后先 HIGH 优先级，订阅完成切回 BALANCED，MTU 后延迟 500ms 再发现服务。
2. **两个通知通道（aec8 + feab）都要订阅完**才发握手，顺序不能变。
3. **API ≤32 的 2 参 `onCharacteristicChanged` 必须保留**，否则老机器收不到通知。
4. **帧头 [4..5] 是 cmd 不是 transMode**；`deviceType` 只在 app 侧判断是否走文件传输分支，不进帧。
5. **uint32 不能符号扩展**（否则刷机 COMPLE 不回 ACK，60s 超时）。
6. **信道响应 0x4E2B 复用**，靠最近发出的命令区分语义，不能只看响应码。
7. **CRC16 大端**追加在帧尾，覆盖头 + 数据。
8. 刷机分包固定 **128 字节**，与协商 MTU 无关。
9. 终端 TextView 可长按复制，**会吞掉点击**——点击类监听要挂别处。
10. 首页列表链式查询的**临时缓冲区 + 一次性替换**是刻意设计（避免闪烁/错序），别改成逐帧刷新。

---

## 15. 已知限制

- **仅覆盖 2 代及更早机型**（Shark 2 代、Seal 1S）；3 代及更晚机型、其它型号需先抓 GATT 表与握手响应自行适配
- 部分能力（如位置共享 0x4E30）未实现
- protobuf 为手写实现，字段号来自实测通信分析，未覆盖协议全部字段
- 刷写固件有变砖风险，请自行承担（详见 README 与 App 内风险告知）

---

## 16. 维护约定

- 协议相关改动**必须真机验证**，不能只凭推理
- 涉及"依据实测行为还原／勿改"的逻辑，改动前必须真机验证，不要凭推理修改
- 所有 UI 文案中英文双语同步；法律文本不翻译
- 新增/修改协议代码时，同步更新本文档第 6/7 节的表格
