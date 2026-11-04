# 开发文档（面向接手与二次开发）

本文记录项目的开发思路、代码结构、通信协议、模块职责、扩展方式，以及开发过程中遇到的问题。

这份文档主要面向需要阅读、维护或继续开发项目的人。除了说明代码如何工作，也会记录一些设备端的实际行为和已经验证过的限制。

**特别注意：部分通信流程存在设备端的特殊要求，不要仅凭代码风格或常规 BLE 开发经验随意修改。** 文中标注了需要保留的时序、参数和处理方式，修改前应先确认原因，并进行对应的真机测试。

建议阅读顺序：

1. 第 2 节：项目由来
2. 第 3 节：整体架构
3. 第 5、6、7 节：BLE 连接与通信协议
4. 第 8、9 节：频道操作与固件升级
5. 第 12 节：二次开发指引
6. 第 14 节：已踩过的坑

---

## 1. 项目简介

这是一个完全离线、无需登录的 Android BLE 工具，通过手机直接连接小米对讲机，用于读取和修改频道、设备参数，以及进行固件升级。

### 机型范围

目前主要适配小米对讲机 2 代及更早的机型：

- **Shark（小米对讲机 2）：** 主要开发和测试机型。
- **Seal（小米对讲机 1S）：** 已完成部分代码适配，但尚未进行真机验证。

Dolphin（小米对讲机 1 代）和 Lite 已加入机型识别及部分协议分析，但通信功能或固件传输流程尚未完整移植，不能视为已经支持。

3 代及更新机型尚未适配、验证。新增机型前，需要先获取设备的 GATT 服务表和握手响应，具体见第 13 节。

### 基本信息

| 项目 | 内容 |
|---|---|
| applicationId | `rex.medicfurry.miradio` |
| 命名空间 | `com.medicfurry.walkieoffline` |
| minSdk | 24 |
| targetSdk | 34 |
| compileSdk | 36 |
| 开发语言 | Kotlin |
| UI | ViewBinding |
| 异步处理 | Kotlin Coroutines |
| Java 版本 | 17 |

应用可以与官方 App 共存安装，不会覆盖原有应用。

---

## 2. 项目由来与技术来源

本项目早期参考了 GitHub 用户 **Darkhorse** 的开源项目：

[Mi-Walkie-Talkie-Plus](https://github.com/Mi-Walkie-Talkie-by-Darkhorse/Mi-Walkie-Talkie-Plus)

参考分支：`2.13.7-plus`

在此基础上，我使用 Kotlin 重新编写了 Android 端实现，逐步形成独立的离线版本。它不是对原版 APK 的简单修改，也不是对官方 SDK 的封装。

项目没有使用官方 SDK，也没有公开的官方通信协议文档。开发过程中主要通过以下方式分析设备行为：

1. **代码分析：** 参考官方 App 的部分实现，分析设备类型、命令码、消息字段及升级流程。
2. **BLE 通信分析：** 结合通信抓包，分析帧结构、命令交互和设备响应。
3. **固件逆向：** 分析固件中的协议处理逻辑、频道数据结构及部分隐藏功能。
4. **真机测试：** 对具备测试条件的机型和功能进行实际验证，检查连接、握手、频道查询及固件升级等流程。
5. **社区方案参考：** 对照其他开源项目的实现，辅助理解部分设备行为。

需要注意，以上方法并不意味着所有协议分支和功能都已经通过真机验证。不同机型的适配程度不同，部分内容来自代码分析或固件逆向推断，另有部分功能已经完成代码实现，但仍等待对应设备验证。

代码中出现的“依据实测行为还原”或“勿改”等注释，是为了标记那些经过实际测试、需要保留特定行为的逻辑。

例如：

- MTU 请求值为 163。
- 两个 BLE 通知通道都需要订阅。
- 服务发现前需要延迟 500ms。
- 固件数据包固定为 128 字节。

这些行为不一定符合常见 BLE 开发习惯，但有对应的设备行为依据。修改前应先确认其作用，不要仅为了代码简化而删除。具体说明见第 14 节。

> 文档中的部分技术分析、协议解释和实现说明使用了 AI 辅助整理。协议分析及 Android / BLE 运行时行为存在一定复杂性，部分推断可能存在偏差。涉及设备行为的结论应以代码、抓包和实际测试为准。

### 第三方项目声明

第三方软件的名称、商标、原始代码、图标、文档及其他受保护内容，其权利归各自权利人所有。

本项目是非官方第三方项目，与相关软件开发者或权利人之间不构成隶属、合作、授权、赞助或背书关系，也不保证与其所有版本持续兼容。

---

## 3. 整体架构

项目主要分为 UI 层、BLE 会话层、协议层和支撑模块。

```text
┌──────────────────────────────────────────────────────────┐
│ UI 层  (ui/)                                              │
│                                                          │
│ DeviceScanActivity → MainActivity → ChannelSetting/Edit  │
│ FirmwareUpdateActivity / About / RiskDisclosure / Easter │
│                                                          │
│ 全部继承 BaseActivity，统一处理语言切换                   │
├──────────────────────────────────────────────────────────┤
│ 会话层  BleConnector (ble/)                               │
│                                                          │
│ GATT 连接、MTU、通知订阅、握手、命令队列、响应分发         │
│ 对外通过 StateFlow 暴露 state / channels / deviceParam   │
├──────────────────────────────────────────────────────────┤
│ 协议层  protocol/                                         │
│                                                          │
│ FrameCodec（帧编解码）                                    │
│ ProtoIO（手写 protobuf wire format）                     │
│ SealProtos（消息模型）                                    │
│ Crc16 / FreqFormat / CssTones                             │
├──────────────────────────────────────────────────────────┤
│ 支撑模块                                                  │
│                                                          │
│ data/PrefsManager（偏好设置）                              │
│ debug/DebugLog（日志总线）                                │
│ ConnectorHolder（跨页面持有 connector 实例）              │
└──────────────────────────────────────────────────────────┘
```

### 数据流向

整体数据流如下：

```text
UI
 │
 ▼
BleConnector
 │
 ▼
BLE 命令发送
 │
 ▼
设备响应
 │
 ▼
FrameCodec.FrameParser
 │
 ▼
帧切分与 CRC 校验
 │
 ▼
按 cmd 分发
 │
 ▼
SealProtos 解码
 │
 ▼
更新 StateFlow
 │
 ▼
UI 收集状态并刷新
```

UI 层调用 `BleConnector` 的方法发送命令。设备响应经过 `FrameCodec.FrameParser` 切帧和校验后，根据命令码分发，再由 `SealProtos` 解码。

解码后的数据更新对应的 `StateFlow`，页面通过收集状态完成刷新。

关键收发过程同时写入 `DebugLog`，主页的「调试终端」用于实时查看通信和状态变化。

---

## 4. 目录与文件职责

主要代码位于：

```text
app/src/main/java/com/medicfurry/walkieoffline/
├── MiWalkieApp.kt
│   应用入口，持有全局 PrefsManager
│
├── ble/
│   ├── BleScanner.kt
│   │   BLE 扫描，输出设备列表 Flow
│   ├── BleConnector.kt
│   │   核心模块：GATT 连接与协议会话
│   └── ConnectorHolder.kt
│       跨 Activity 共享 connector 实例
│
├── protocol/
│   ├── FrameCodec.kt
│   │   帧格式、命令码常量、流式解析器
│   ├── ProtoIO.kt
│   │   手写 protobuf varint / length-delimited
│   ├── SealProtos.kt
│   │   消息模型及常量（结果码、操作类型等）
│   ├── Crc16.kt
│   │   CRC16 帧校验
│   ├── FreqFormat.kt
│   │   Hz 与频率字符串之间的转换
│   └── CssTones.kt
│       亚音列表（模拟、数字正码和反码）
│
├── data/
│   └── PrefsManager.kt
│       语言、首启标记、刷机倒计时开关等
│
├── debug/
│   └── DebugLog.kt
│       全局日志总线，供终端显示和复制日志使用
│
└── ui/
    ├── BaseActivity.kt
    │   基类，处理语言切换后的页面重建
    ├── FirstRunActivity.kt
    │   首次启动：刷机风险和法律风险提示
    ├── PermissionActivity.kt
    │   蓝牙及定位权限说明
    ├── DeviceScanActivity.kt
    │   设备扫描与选择
    ├── MainActivity.kt
    │   主页：设备参数、频道入口、调试终端及彩蛋
    ├── ChannelSettingActivity.kt
    │   频道列表（自定义 / 预设）
    ├── ChannelEditActivity.kt
    │   频道编辑
    ├── FirmwareManager.kt
    │   固件目录扫描、文件选择及升级启动
    ├── FirmwareUpdateActivity.kt
    │   固件升级进度页及状态机
    ├── AboutActivity.kt
    │   关于页面：版本、GitHub、风险告知及语言切换
    ├── RiskDisclosureActivity.kt
    │   风险告知与软件声明（HTML 渲染）
    ├── EasterEggActivity.kt
    │   彩蛋页面，支持缩放大图
    ├── ZoomableImageView.kt
    │   自定义双指缩放 ImageView
    ├── TextLayout.kt
    │   按宽度断行且不拆分单词的排版
    └── MaxHeightScrollView.kt
        限高滚动容器
```

`BleConnector.kt` 是整个项目最重要的模块之一，负责 BLE 连接、设备握手、命令发送和响应处理。涉及通信行为的修改，建议先阅读该文件及对应协议实现。

---

## 5. BLE 连接与握手时序

连接和握手必须遵循以下顺序。该流程已经在 Shark 真机上进行验证，涉及的设备分支仍需按各自验证情况判断。

### 连接流程

1. 调用 `connectGatt`，等待 GATT 连接成功。
2. 调用 `requestConnectionPriority(HIGH)`，然后请求 MTU：

   ```kotlin
   requestMtu(163)
   ```

   **请求值是 163，不是 247。**

   Android 14 及更新版本的系统蓝牙栈可能直接协商为 517。当前帧收发和 128 字节固件分包均不依赖协商后的 MTU 值，因此无需针对 517 单独处理。

3. 收到 `onMtuChanged` 后，延迟 500ms，再调用 `discoverServices`。
4. 根据 GATT 服务识别设备型号。
5. 依次订阅两个通知通道，写入 CCCD `2902`。
6. 两个通知通道都订阅完成后，切换连接优先级并发送握手。
7. 收到设备响应后，根据结果继续初始化或处理机型纠正。

### 设备识别

官方设备类型枚举：

| 设备类型 | 数值 |
|---|---:|
| DOLPHIN | `1` |
| SHARK | `4` |
| SEAL | `5` |
| LITE | `36611` |

当前服务识别规则：

| GATT 服务前缀 | 对应机型 | deviceType |
|---|---|---:|
| `0000aee7` | Seal | `5` |
| `0000fee7` | Dolphin | `1` |
| `0000aec7` | Lite | `36611` |
| `0000fdab` | Shark | `4` |

需要注意：部分 1S 新固件只暴露 `fdab` 服务，不再暴露 `aee7`。因此，仅凭服务 UUID 无法区分 Shark 和这部分 Seal 设备。

针对该情况，目前使用以下处理方式：

- 优先使用按 MAC 地址保存的上次握手学习结果。
- 没有历史记录时，默认按 Shark 尝试。
- 如果设备返回 `REFUSE`，且响应 `field4` 带回实际 `deviceType`（4 或 5），则按返回值纠正机型并重新握手一次。
- 将学习结果按 MAC 地址保存，后续连接时优先使用。

Lite 使用独立协议。识别为 Lite 后不进入 Seal / Shark 握手流程，而是直接提示当前功能限制。

### 通知订阅与握手

通知通道必须依次订阅：

1. `aec8`
2. `feab`

两者都需要写入 CCCD `2902`。只有 `feab` 订阅完成后，才切换连接优先级并发送握手帧 `0x4E21`。

具体顺序：

```text
connectGatt
    │
    ▼
连接成功
    │
    ▼
requestConnectionPriority(HIGH)
    │
    ▼
requestMtu(163)
    │
    ▼
onMtuChanged
    │
    ▼
延迟 500ms
    │
    ▼
discoverServices
    │
    ▼
订阅 aec8
    │
    ▼
订阅 feab
    │
    ▼
requestConnectionPriority(BALANCED)
    │
    ▼
发送 0x4E21 握手
    │
    ▼
ALLOW / REFUSE
```

设备返回 `ALLOW(4)` 后，依次下发：

1. 设备参数查询 `0x4E23`
2. 信道状态查询 `0x4E26`
3. 信道列表查询 `0x4E29`

设备返回 `REFUSE(5)`，且响应 `field4` 带回 `deviceType`（4/5）时，改用返回值重试握手一次，并保存对应 MAC 的学习结果。

响应帧样例：

```text
08 01 10 05 20 05 28 00
```

### GATT 特征

UUID 后缀统一为：

`-0000-1000-8000-00805f9b34fb`

| UUID | 方向 | 作用 |
|---|---|---|
| `0000aec7-...` | App → 设备 | 写命令通道 |
| `0000aec8-...` | 设备 → App | 主通知通道 |
| `0000feab-...` | 设备 → App | 第二通知通道（fdab 服务上同样需要订阅） |
| `00002902-...` | — | CCCD，通知订阅使用 |

> **注意：`onCharacteristicChanged` 存在两个方法签名。**
>
> API 33 及以上使用 3 参数版本，API 32 及以下使用 2 参数版本。
>
> 如果只重写 3 参数版本，在 Android 11（API 30）等旧系统上将无法收到通知，表现为设备没有响应。代码中两个版本都需要保留。

---

## 6. 帧格式与命令码

### 6.1 帧结构（`FrameCodec`）

```text
[0]     0xFE
[1]     0x01
[2..3]  totalLen = 8 + dataLen + 2     大端
[4..5]  cmd 命令码                     大端
[6..7]  serial（0..0xFFFE 递增）       大端
[8..]   protobuf payload
末尾+2  CRC16（覆盖前面所有字节）      大端 [hi, lo]
```

`[4..5]` 是命令码 `cmd`，不是 `transMode`。修改帧解析或封装逻辑时，需要注意这一点。

### 6.2 命令码

| cmd | 含义 | 响应 |
|---|---|---|
| `0x4E21` | Connect 握手 | `0x4E22`（+1） |
| `0x4E23` | DeviceParam 查询 | `0x4E25`（+2） |
| `0x4E24` | DeviceParam 设置 | `0x4E25` |
| `0x4E26` | ChannelState 查询 | `0x4E28`（+2） |
| `0x4E27` | ChannelState 设置（双频开关 / deviceMode 切换） | `0x4E28` |
| `0x4E29` | ChannelInfo 查询（读取频道列表） | `0x4E2B`（+2） |
| `0x4E2A` | ChannelInfo 增删改 | `0x4E2B` |
| `0x4E30` | LocationInfo（本项目裁剪） | — |
| `0x4E2C` | 升级控制（START / COMPLE / REBOOT） | `0x4E2E` |
| `0x4E2D` | 升级数据包（128 字节/包） | `0x4E2E` |
| `0x4E2E` | 设备升级响应（result + 已确认 offset） | — |

`0x4E27` 中的 `deviceMode` 取值：

- `3`：预设频道扫描
- `4`：全频率扫描
- `0`：退出扫描

`0x4E29` 的 `chType`：

- `0`：公众频道
- `1`：远距离频道
- `2`：自定义频道
- `3`：FRS 查询类型（当前固件上的列表路径存在问题，不用于读取 FRS 固定表）

### 响应码复用

信道类命令存在响应码复用：

- `0x4E29` 查询频道列表，响应 `0x4E2B`。
- `0x4E2A` 增删改频道，响应同样是 `0x4E2B`。

因此，不能只根据响应码判断响应语义，还需要结合最近发出的信道命令区分：

- 查询：流式列表响应。
- 增删改：操作结果确认。

代码中通过 FIFO 期望队列和命令配对处理。不要改成只按响应码分发，否则可能将查询响应误认为操作确认，或反过来。

---

## 7. protobuf 消息

相关实现位于 `SealProtos` 和 `ProtoIO`。

项目没有引入 protobuf 运行库，而是手动实现 wire format，包括：

- varint
- length-delimited

这样可以减少额外依赖，并保持 APK 轻量。

### 7.1 ChannelInfo 字段

编码入口：`encodeChannelInfo`

| field | 含义 | 备注 |
|---|---|---|
| 1 | no | 信道号 |
| 2 | type | `0=PUBLIC`、`1=REMOTE`、`2=USERDEF`、`3=FRS`（`1.0.5+M1` 固件开放的隐藏页） |
| 3 | txPower | 设备级功率档：`1=低(3W)`、`2=高(5W)`、`3=0.5W`（`1.0.5+M1` 新增，官方固件拒收） |
| 4 | rxFreq | 接收频率，单位 Hz |
| 5 | txFreq | 发射频率，单位 Hz |
| 6 | name | 信道名，使用 GB2312 编码 |
| 7 | sq | 每频道静噪字段，编辑回写时透传设备回读值，生效性待验证 |
| 8 | vox | 每频道 VOX 字段，编辑回写时透传设备回读值，生效性待验证 |
| 9 | rxCss | 接收亚音，`0=关闭` |
| 10 | txCss | 发射亚音，`0=关闭` |

### 7.2 ChannelStateOperate 字段

字段对应关系：

```text
version       = 1
result        = 2
option        = 3
deviceMode    = 4
stateMode     = 5
ch1           = 6
ch2           = 7
currentChId   = 8
```

`option`：

- `ST_UPDATE=0`
- `ST_QUERY=1`

`deviceMode`（SHARK_MODE）：

| 数值 | 含义 |
|---:|---|
| 0 | NORMAL |
| 1 | SOS_T |
| 2 | SOS_R |
| 3 | 预设频道扫描 |
| 4 | 全频率扫描 |
| 5 | TEAM_M |
| 6 | TEAM_S |

扫描模式下设备会拒绝频道写入，App 已增加对应拦截和提示。

扫描期间每秒轮询 `0x4E26` 获取状态。设备锁定的频道会通过 `ch1` 回显。

### 7.3 DeviceParam 字段

DeviceParam 涉及以下字段：

```text
version
result
verHw
verSoft
deviceName (GB2312)
bleName (UTF-8)
charge
txPower
timeDisplay
voiceType
voiceAlert
pwSaveMode
```

其中 `verHw` 用于固件选择时匹配硬件版本，例如：

`shark_*_hw<N>.bin`

### 7.4 关键枚举

| 常量组 | 取值 |
|---|---|
| Connect result | `DISCONNECT=0`、`CONNECT=1`、`ALLOW=4`、`REFUSE=5`、`WAIT=6`、`LOWPOWER=7`、`BUSY=12` |
| ChannelOperate | `INSERT=0`、`DELETE=1`、`MODIFY=2`、`QUERY=3` |
| ChannelType | `PUBLIC=0`、`REMOTE=1`、`USERDEF=2`、`FRS=3`（页面及列表空间） |
| Channel result | `OK=0`、`ERROR=1`、`EMPTY=2`、`FORBIDE=3`、`QUERY_CONTINUE=4`、`QUERY_FINISH=5` |
| Upgrade | `START=1`、`COMPLE=2`、`REBOOT=3` |

### uint32 编码注意事项

> **坑：`uint32` 字段必须按无符号 32 位编码，不能发生符号扩展。**

如果按有符号 `long` 编码，可能产生 10 字节的 varint，导致设备解析失败。

实际测试中，CRC32 字段发生符号扩展后，设备对 `COMPLE` 命令完全不返回 ACK，最终等待 60 秒超时。

对应实现：

`ProtoIO.writeUInt32Field`

修改 protobuf 编码时，必须保留无符号 32 位处理方式。

---

## 8. 频道操作流程

### 8.1 频道查询

`0x4E29` 的查询是链式分页流程，不能当作一次普通请求处理。

当前处理顺序：

1. 先显示本地缓存的频道列表。
2. 发送 `QUERY + chType=USERDEF(2)`。
3. 收到 `result=QUERY_CONTINUE(4)` 时，将频道加入临时缓冲区。
4. 立即发送下一页 QUERY。
5. 收到 `EMPTY(2)`、`QUERY_FINISH(5)`，或达到 10 秒兜底时间后，结束查询。
6. 将临时缓冲区中的结果一次性替换到正式列表。

这样可以避免逐帧刷新造成列表闪烁、错序或短暂显示不完整。

### 8.2 频道操作

- 单击自定义频道：执行跳台激活，发送 `0x4E2A + MODIFY`，并设置 `isMajor=true`。
- 长按频道：打开删除或编辑菜单。
- 删除频道：需要二次确认。
- 当前频道行的 RX / TX 文字使用蓝色 `0xFF0076FF` 高亮。
- 设备处于扫描模式（`deviceMode=3/4`）或组队模式（`deviceMode=5/6`）时，会拒绝频道写入，App 侧会同步拦截并提示用户。

### 8.3 自定义频道数量扩展

自定义频道读取采用官方 USERDEF 查询链路，并在收到 `EMPTY` 后增加显式 `no` 探测。

已在小米对讲机 2 和 `1.0.5+M1` 修改固件上进行真机测试，29 个频道均能完整同步。

原因是修改固件虽然将频道数量扩展到了 29 个，但原有列表查询路径仍然存在 20 个频道的边界限制。

因此，App 在官方查询链路结束后，继续逐槽探测：

```text
chType = 2
no = 20..28
```

相关常量：

```kotlin
CUSTOM_PROBE_MAX_NO = 28
```

这里的最大 `no` 为 28，对应第 29 个频道。

补充探测会持续扫描到设定上限，再结束查询。

**不要将 `0x4E29 chType=3` 用作 FRS 频道列表查询。**

当前固件中，这条路径实际对应 FM 数据区（`h=0x14`），不能作为普通频道列表使用。此前曾误接入该路径，现已回退。

### 8.4 频道槽位选择

频道槽位相关逻辑：

- `ChannelEditActivity.MAX_CHANNEL_SLOTS`：动态槽位上限，下限为 29。
- `SLOT_PICK_BOUND=200`：新增和编辑时，槽位选择范围为 1～200。

目前无法通过 BLE 协议准确读取设备的实际容量。

测试中发现，空槽、越界和空记录 INSERT 三种情况会返回相同的 `6B EMPTY` 响应，因此无法仅凭协议响应区分，也无法通过逐个探测可靠地判断容量。

所以当前采用以下方式：

1. App 将槽位选择范围放宽到 1～200。
2. 实际容量由设备固件裁决。
3. 如果写入超出设备容量，设备会拒绝操作，App 提示失败。
4. 写入成功则说明该槽位在设备可用范围内。

这种方式不需要针对不同固件容量修改 App。

### 8.5 FRS 频道组

FRS 频道组位于预设频道面板的第三组宫格，使用本地固定表显示。

- 共 22 个频道。
- 频率数据逐字节取自 `1.0.5+M1` 固件 body `0x319DC` 内置表。
- 频道名称由 App 本地生成。
- 点击频道后，通过 `0x4E27` 执行跳台。

跳台参数：

```text
no
type = FRS_RECORD(9)
```

这里需要特别区分两个 FRS 类型编号。

**页面 / 列表空间：`3`**

- 预设频道页面第三组使用类型 3。
- `FUN_0800faa0(3, no)` 对应页面及列表空间。

**频道记录 / 状态回显空间：`9`**

- `0x4E28` 的 `ch1` 会回显 `type=9`。
- 真机测试中，北美家用频道 1 对应 `462.5625 MHz`。
- 实际跳台时使用 `type=9`。

因此，页面类型 3 和频道记录类型 9 不能混为一谈。

### FRS BLE 列表路径的限制

目前不使用 `0x4E29` 读取 FRS 固定表，原因是测试发现该路径存在固件侧缺口：

- `0x4E29 chType=3` 读到的是 NVM 残留表。
- 其中包含 `h=0x14` 区域的历史自定义频道残留数据。
- 分页游标不会正常前进，裸续页可能反复返回 `no=0`。
- 显式携带 `no` 虽然可以枚举，但读到的仍是残留数据。
- `chType=9` 查询没有响应。

固件当前没有将 `0x29` 列表查询正确接入 FRS 固定表。

如果后续固件版本补上这条路径，App 可以恢复查询式 FRS。`BleConnector` 中已保留 FRS 枚举相关代码，但续页需要显式携带 `no`。

### 8.6 扫描模式

主页提供扫描模式入口。

进入和退出命令：

| 操作 | 命令 |
|---|---|
| 预设频道扫描 | `0x4E27` + `deviceMode=3` |
| 全频率扫描 | `0x4E27` + `deviceMode=4` |
| 退出扫描 | `0x4E27` + `deviceMode=0` |

进入扫描模式后：

- App 每 1 秒轮询一次 `0x4E26`。
- 读取设备返回的 `deviceMode`。
- 通过 StateFlow 更新主页显示。
- 锁定频道通过 `ch1`（field 6）回显。
- 收到锁定频道后，顺带刷新主页频道卡片。

扫描和组队模式下，设备会拒绝频道写入。App 侧在以下操作中增加了 `deviceMode` 检查：

- `activateCustom`
- `activateFrs`
- 新增频道

---

## 9. 固件刷写流程

### 9.1 固件文件选择

默认固件目录：

```text
/sdcard/Mi-walkie-talkie.firmware/
```

也可以通过文件选择器手动选择固件。

选择固件时，根据设备型号和硬件版本过滤文件。机型枚举与官方 `DeviceTypeEnum` 保持一致：

```text
DOLPHIN = 1
SHARK   = 4
SEAL    = 5
LITE    = 36611
```

不同机型的固件文件处理方式如下。

| 机型 | 文件识别规则 | 当前状态 |
|---|---|---|
| Shark（4） | `shark_*_hw<N>.bin` | 已适配 |
| Seal（5） | `seal_*_hw<N>.bin` | 已移植升级状态机，尚未进行 1S 真机刷写验证 |
| Dolphin（1） | `mcu_ble_*.bin` | 文件识别已适配，传输流程未实现 |
| Lite（36611） | 官方服务器下发固件信息 | 本地刷写传输层未移植 |

具体规则：

**Shark（4）**

按 `verHw` 匹配：

```text
shark_*_hw<N>.bin
```

如果无法获取硬件版本，则列出所有：

```text
shark_*.bin
```

**Seal（5）**

按 `verHw` 匹配：

```text
seal_*_hw<N>.bin
```

未知时使用相同的兜底方式。

Seal 与 Shark 共用同一套官方升级状态机，但目前仅完成代码移植和适配，尚未进行 1S 真机刷写验证。

**Dolphin（1）**

官方文件格式：

```text
mcu_ble_<hi16>_<lo16>.bin
```

硬件版本 `versionHW` 需要通过遗留 MITALK 协议查询。目前本应用无法读取该值，因此会列出：

```text
mcu_ble_*.bin
```

Dolphin 的固件传输属于遗留 MITALK 裸数据分支，目前尚未实现。入口会明确提示该限制。

**Lite（36611）**

官方流程为：

```text
服务器查询 FirmwareInfo
        │
        ▼
下载固件
        │
        ▼
LiteProtos
        │
        ▼
自定义 BLE 传输层
        │
        ▼
固件刷写
```

Lite 没有对应的本地目录刷写分支。本项目尚未移植 `lite/e` 传输层，因此入口会提示暂不支持，需要 Lite 真机进行后续验证。

### 9.2 Shark / Seal 升级流程

当前已移植的升级状态机使用以下命令：

1. 读取固件文件。
2. 发送 `0x4E2C` + `START=1`，携带固件总字节数和总包数。
3. 按 128 字节/包发送 `0x4E2D`。
4. 每包等待设备通过 `0x4E2E` 返回响应，包含 `result` 和已确认的 `offset`。
5. 进度按已确认字节数计算：

   ```text
   progress = 已确认字节 / 总字节
   ```

6. 数据发送完成后，发送 `0x4E2C` + `COMPLE=2`，携带 CRC32，等待设备校验。
7. 根据设备返回的状态更新升级进度。
8. 升级成功后提示对讲机将重启。

升级状态：

| 状态值 | 含义 |
|---:|---|
| `1` | 传输中 |
| `5` | 成功 |
| `6` | 失败 |
| `7` | 低电量失败 |

### 9.3 刷机注意事项

- Android 11 及以上读取目录需要「所有文件访问权限」，代码会引导用户进入设置页。
- 刷机过程中禁止返回，页面只提示“正在升级固件，请稍候……”。
- 关于页隐藏开关：连续点击版本号 5 次，可以关闭刷机确认的 10 秒倒计时。
- 固件数据包固定为 128 字节，与协商 MTU 无关。
- 刷写过程中不要断开蓝牙、关闭 App 或关闭设备电源。

---

## 10. 状态与数据流

连接状态、频道列表、设备参数、双频状态和设备模式均通过 `StateFlow` 暴露。

这是有意采用的状态管理方式，不要随意改成普通回调。

使用 StateFlow 的原因：

- 页面重建后可以重新获取当前状态。
- 从子页面返回主页时，状态不会因为页面销毁而丢失。
- UI 可以持续收集状态并自动刷新。
- 避免多个页面分别维护一份容易不同步的数据。

### 调试日志

日志统一通过 `DebugLog` 管理。

- 全局日志总线最多保留 300 行。
- 每条日志带时间戳。
- 主页终端以 150ms 节流批量刷新。
- 节流只影响 UI 刷新频率，不会主动丢弃日志。
- 终端下方的「复制日志」按钮会将全部日志写入剪贴板。

> **注意：** 终端 TextView 开启了 `textIsSelectable`，会拦截部分点击事件。因此彩蛋的点击监听挂在「调试终端」标题上，而不是终端文本区域。

---

## 11. 页面与导航

页面启动和跳转关系：

```text
启动
 │
 ▼
FirstRunActivity（仅首次）
 │
 ▼
PermissionActivity
 │
 ▼
DeviceScanActivity
 │
 ▼
MainActivity
 │
 ├── ChannelSettingActivity
 │       └── ChannelEditActivity
 │
 ├── FirmwareUpdateActivity
 │       └── FirmwareManager 负责选择固件
 │
 └── AboutActivity
         │
         ├── RiskDisclosureActivity
         │
         └── EasterEggActivity
```

### 语言切换

支持中文和英文切换。

修改 `PrefsManager.language` 后调用 `recreate()`。`BaseActivity.onResume` 会检查语言状态变化，并自动重建其他页面，无需重启 App。

语言切换按钮的文案采用反向显示：

- 中文模式显示 `Language`。
- 英文模式显示 `语言`。

这样可以帮助误切换到外语的用户找到切回入口。

---

## 12. 二次开发指引

### 12.1 新增一条协议命令

1. 在 `FrameCodec` 中添加命令常量：

   ```kotlin
   const val CMD_XXX = 0x....
   ```

2. 在 `SealProtos` 中添加对应消息模型。
3. 根据通信分析得到的字段号，实现 encode / decode。
4. 在 `BleConnector` 中增加发送方法：

   ```text
   消息编码
       ↓
   FrameCodec.encode(cmd, payload)
       ↓
   写入 aec7
   ```

5. 如果响应码不符合现有规律，需要在 `handleFrames` 的分发逻辑中增加分支，并同步检查期望队列。
6. 使用主页调试终端观察收发过程，确认设备实际响应。

新增协议命令时，不要只验证发送是否成功，还需要检查设备响应内容及后续状态变化。

### 12.2 新增主页设置项

1. 在 `activity_main.xml` 中参考 `RadioSettingItem` 的布局风格，添加标题和灰色描述。
2. 在以下两个文件中同步增加文案：

   ```text
   res/values/strings.xml
   res/values-en/strings.xml
   ```

3. 在 `MainActivity.setupSettingItems()` 中绑定点击事件。
4. 调用对应的 `BleConnector` 方法发送命令。

### 12.3 新增页面

1. 创建 `XxxActivity : BaseActivity()`。
2. 创建对应布局文件：

   ```text
   activity_xxx.xml
   ```

3. 使用 ViewBinding 自动生成的 `ActivityXxxBinding`。
4. 在 `AndroidManifest.xml` 中注册：

   ```xml
   android:exported="false"
   ```

新增页面应继承 `BaseActivity`，否则无法直接使用项目现有的语言热切换处理。

### 12.4 修改文案或添加图片

**文案**

中文和英文文案分别位于：

```text
res/values/strings.xml
res/values-en/strings.xml
```

修改或新增普通 UI 文案时，需要同步维护两个文件。

法律类文本（例如软件声明）标记为 `translatable="false"`，不随界面语言切换。

**图片**

- 普通图片放入 `res/drawable-nodpi/`。
- 需要保留原始尺寸和细节的大图放入 `assets/`。

大图解码时使用：

```text
BitmapFactory
ARGB_8888
inScaled = false
```

具体实现可以参考彩蛋页面。

---

## 13. 调试方法

| 手段 | 用途 |
|---|---|
| 主页「调试终端」 | 实时查看协议收发、状态机和错误信息 |
| 「复制日志」按钮 | 复制全部日志，便于提交 issue 或交流排查 |
| `adb logcat` | 查看同时写入 logcat 的 `DebugLog` 输出 |
| 关于页连续点击版本号 5 次 | 切换刷机确认 10 秒倒计时开关 |
| 主页「调试终端」标题连续点击 5 次 | 打开彩蛋页面，检查手势和页面跳转 |
| `onServicesDiscovered` 日志 | 查看完整 GATT 服务表 |

### 新增机型时的基本检查

更换设备或适配新型号时，建议先完成以下工作：

1. 扫描并记录设备广播信息。
2. 连接设备，获取完整 GATT 服务表。
3. 记录各服务及特征 UUID。
4. 确认写入通道和通知通道。
5. 订阅通知，抓取握手请求和响应。
6. 确认设备返回的 `deviceType`。
7. 分析设备使用的协议分支。
8. 再决定是否需要增加独立的连接和协议处理逻辑。

不要仅凭设备名称或外观判断机型，也不要假设不同代设备一定使用相同的 GATT 服务和协议。

---

## 14. 已踩过的坑

以下内容是开发过程中已经确认需要注意的设备行为或实现限制。修改相关代码前，先确认是否会影响这些行为。

### 14.1 BLE 连接与通知

1. **MTU 请求值是 163，不是 247。** 连接后先请求 HIGH 优先级，通知订阅完成后切回 BALANCED。MTU 回调后延迟 500ms 再发现服务。

2. **`aec8` 和 `feab` 两个通知通道都必须订阅完成后才能发送握手。** 订阅顺序不能随意调整。

3. **API ≤32 的 2 参数 `onCharacteristicChanged` 必须保留。** 否则旧系统可能收不到通知。

4. **Android 14+ 可能协商出 MTU 517。** 这是系统蓝牙栈行为，当前帧收发及 128 字节固件分包不依赖该值。

### 14.2 帧与协议

5. **帧头 `[4..5]` 是 `cmd`，不是 `transMode`。** `deviceType` 只在 App 侧用于判断设备类型和升级分支，不进入帧结构。

6. **uint32 不能发生符号扩展。** 否则刷机 `COMPLE` 可能不返回 ACK，最终等待 60 秒超时。

7. **CRC16 使用大端格式。** 追加在帧尾，校验范围覆盖帧头及数据。

8. **信道响应 `0x4E2B` 被查询和增删改命令共用。** 必须结合最近发送的命令判断响应语义，不能只看响应码。

9. **刷机数据包固定为 128 字节。** 这属于设备端的固定要求，与协商 MTU 无关。

### 14.3 UI 与状态处理

10. **终端 TextView 会吞掉点击事件。** 因为启用了 `textIsSelectable`，点击类监听应挂在其他视图上。

11. **频道查询使用临时缓冲区，结束后一次性替换列表。** 这是为了避免逐帧刷新产生闪烁和错序，不要改成每收到一条记录就直接刷新正式列表。

### 14.4 设备识别与握手

12. **fdab-only 的 1S 不能单凭 GATT 服务识别为 Shark。**

    部分 1S 新固件只暴露 `fdab`，与 Shark 的服务特征相同。

    当前通过以下方式处理：

    - 使用按 MAC 地址保存的握手学习结果。
    - 首次没有记录时默认按 Shark 尝试。
    - 收到 `REFUSE` 后，读取响应 `field4` 返回的实际 `deviceType`。
    - 使用正确类型重新握手，并保存学习结果。

    重试逻辑通过 `handshakeTriedTypes` 防止重复尝试。不要改成不设限制的循环重试。

### 14.5 频道查询与 FRS

13. **续页裸 QUERY 不携带 `chType`。**

    FRS 查询需要等待自定义频道查询链结束后再发送，通过 `queryFrsWhenIdle` 控制。

    响应根据请求发起时记录的 `activeQueryType` 路由到对应列表，不要在收到响应后再猜测类型。

14. **FRS 页面类型 3 与记录类型 9 不是同一个编号。**

    页面和列表空间使用 `3`，频道记录和状态回显空间使用 `9`。当前 FRS 使用本地固定表显示，通过 `0x4E27` 和 `type=9` 跳台。

15. **当前固件的 `0x4E29 chType=3` 不能用于读取 FRS 固定表。**

    该路径会读取 NVM 残留数据，分页游标也无法正常前进；`chType=9` 则没有响应。不要在没有确认固件行为的情况下恢复这条查询路径。

### 14.6 频道拖拽

16. **展开槽位视图可能把目标频道行推到屏幕外。**

    该问题在真机测试中出现过。

    非整理模式下拖拽频道时，App 会先展开 29 个槽位的视图。目标频道前面插入的空槽占位行可能将目标位置推到可视区域之外。

    此时：

    ```kotlin
    findViewHolderForAdapterPosition(targetPos)
    ```

    对尚未完成布局、位于屏幕外的行返回 `null`，导致拖拽启动失败。

    表现为：展开槽位列表后，拖拽立即取消。频道越靠后、前方空槽越多，问题越容易出现。

    当前修复方式：

    1. `beginSlotDrag` 提交展开后的列表。
    2. 调用 `scrollToPositionWithOffset(targetPos)`，先滚动到目标行。
    3. 等待后续布局完成。
    4. 再次获取目标 `ViewHolder`。
    5. 获取成功后启动拖拽。

    拖拽移动由 `ItemTouchHelper` 在 RecyclerView 层处理，不依赖最初按住的视图，因此滚动到目标位置不会中断按住状态。

---

## 15. 已知限制

- **Shark：** 目前主要开发和真机测试机型，主要通信及固件升级功能已完成适配。
- **Seal：** 已完成部分代码适配，包括握手识别和升级状态机移植，但尚未进行 1S 真机验证。
- **Dolphin：** 可以识别机型并识别部分固件文件，频道、参数和遗留 MITALK 传输流程尚未完成适配。
- **Lite：** 可以识别机型，独立协议和 BLE 固件传输层尚未移植。
- **2S、公网对讲机、3 代及更新机型：** 尚未适配。
- 位置共享命令 `0x4E30` 等部分功能未实现。
- protobuf 使用手写实现，字段号来自通信分析，尚未覆盖协议的全部字段。
- 每频道 SQ / VOX 字段已完成透传，但设备端生效情况仍需验证。
- 固件刷写存在设备变砖风险，使用前请阅读 README 和 App 内风险告知。

---

## 16. 维护约定

1. 涉及协议行为的修改，应先分析现有实现和设备响应；具备对应设备时，必须进行真机验证。
2. 对标注了“依据实测行为还原”或“勿改”的逻辑，修改前必须确认其作用，不要仅凭推理或代码风格进行调整。
3. 新增或修改 UI 文案时，中英文资源文件必须同步更新；法律文本按原有规则处理。
4. 新增或修改协议代码时，同步更新本文档第 5、6、7 节的相关内容。
5. 如果某项功能只完成代码适配、尚未进行真机验证，应明确标注，不要将其写成已完整支持。
6. 如果通过抓包、固件逆向或其他设备的日志得出结论，应保留对应证据，并区分推断与真机验证结果。
7. 修改固件升级流程时，必须检查机型分支、固件匹配规则、分包长度、CRC32 编码和设备确认响应，避免影响已经可用的升级流程。

协议和固件相关代码的修改应以设备实际行为为依据。代码能够编译、命令能够发出，并不代表设备已经正确执行。