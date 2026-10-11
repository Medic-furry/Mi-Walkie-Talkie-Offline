# 小米对讲离线（Mi-Walkie-Talkie-Offline）

一个**完全离线、无需登录**的安卓 BLE 工具，用于连接**小米对讲机**并读写频道、设备参数与刷写固件。

> ⚠️ **机型支持范围：目前仅支持 2 代及更早的机型**（Shark 2 代、Seal 1S 等）。
> 3 代及更晚推出的机型未做适配，可能无法连接或功能异常。

本项目属于**非官方第三方项目**，不代表相关软件开发者或权利人的官方产品，
不构成与其之间的隶属、合作、授权、赞助或背书关系。

- 包名（applicationId）：`rex.medicfurry.miradio`（可与官方 APP 同时安装共存，互不覆盖）
- 最低系统：Android 7.0（minSdk 24），目标 targetSdk 34，compileSdk 36
- 语言：Kotlin + ViewBinding + 协程；Java 17

## 支持机型

| 机型 | 识别方式 | 状态 |
|------|---------|------|
| Shark（2 代） | GATT 服务 `0000fdab-...`（deviceType = 4） | ✅ 已适配 |
| Seal（1S） | GATT 服务 `0000aee7-...`（deviceType = 5） | ✅ 已适配 |
| 3 代及更晚机型 | — | ❌ 未适配，未验证 |

设备型号在 BLE 连接后按 GATT 服务自动识别。若手头是更晚的机型，
需要先抓 GATT 服务表与握手响应自行适配（方法见 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) 第 13 节）。

**本项目只面向 2 代及更早机型**，更新的机型不在支持范围内。

## 项目由来：基于什么开发

> **基于 GitHub 用户 \*Darkhorse\* 的开源项目：** [Mi-Walkie-Talkie-Plus](https://github.com/Mi-Walkie-Talkie-by-Darkhorse/Mi-Walkie-Talkie-Plus)（分支 `2.13.7-plus`）

在此基础上，本项目使用 **Kotlin 重写为独立的离线实现**（不是修改版 APK，也不是官方 SDK 封装），
并对设备的通信行为做了技术分析与真机验证，逐项确认帧格式、命令码与交互时序。

**开发目标：**

- 完全免除登录，实现离线本地化运行
- 保留 BLE / MCU / 固件升级相关逻辑
- 支持通过本地文件夹 `/sdcard/Mi-walkie-talkie.firmware/` 刷入固件
- 与官方 APP 共存（包名不同），可同时安装，互不覆盖

代码里标注"依据实测行为还原／勿改"的段落（如 MTU 取值、两个通知通道都要订阅）
是设备端实际要求，**请勿随意"优化"**，详见 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) 第 14 节。

> 该说明中的技术分析、协议解释及部分实现说明由开发过程中使用的 AI 辅助生成。
> 由于协议分析与 Android / BLE 运行时行为存在复杂性，本文内容不保证绝对准确，
> 部分推断可能存在偏差，请自行验证。

第三方软件的名称、商标、原始代码、图标、文档及其他受保护内容，其权利归各自权利人所有，
本声明不转让或授予任何第三方知识产权。本项目不保证与相关第三方软件的所有版本持续兼容。

## 文档

- **[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)** —— 开发文档：项目由来、架构、BLE 握手时序、
  帧格式与命令码表、protobuf 字段、频道与刷机流程、二次开发指引、已踩过的坑。
  **接手开发或二次开发请先读这份。**

## 功能

| 模块 | 说明 |
|------|------|
| 设备扫描 / 连接 | BLE 扫描附近对讲机，选择后建立 GATT 连接并完成协议握手 |
| 频道设置 | 自定义频道 / 预设频道的查询、新增、修改、删除；单击频道即跳台 |
| 对讲机参数 | 对讲机名称、双频模式、功率等级、时间校准 |
| 固件刷写 | 读取 `.bin` 固件并传输刷写（按设备升级流程实现的状态机）。固件默认目录：`/sdcard/Mi-walkie-talkie.firmware/`，也可手动选择文件（Android 11+ 需「所有文件访问权限」） |
| 调试终端 | 主页底部实时显示协议收发与状态日志，可一键复制全部日志 |
| 语言切换 | 中文 / English 双向切换，即时生效无需重启 |
| 风险告知 | 关于页入口，集中展示刷机风险、法律提示与蓝牙定位权限说明 |
| 软件声明 | 关于页「风险告知」页内附完整的授权与免责声明 |

> 彩蛋：主页「调试终端」标题连点 5 次可打开项目形象图页（支持双指缩放）。

## 编译

```bash
./gradlew assembleDebug     # 调试包，输出到 app/build/outputs/apk/debug/
./gradlew assembleRelease   # 发布包（未启用混淆）
```

直接用 Android Studio 打开本目录亦可（Gradle 会自动同步依赖）。

## 目录结构

```
app/src/main/
├── java/com/medicfurry/walkieoffline/
│   ├── ble/          BLE 扫描与连接、协议收发（BleScanner / BleConnector）
│   ├── protocol/     协议编解码：帧结构、CRC16、频率与亚音、protobuf 生成类
│   ├── data/         偏好存储（语言、首启标记等）
│   ├── debug/        全局调试日志总线（终端显示 / 复制日志的数据源）
│   └── ui/           各页面：扫描、主页、频道、固件、关于、风险告知等
├── res/              布局、字符串（values 中文 / values-en 英文）、图标
└── assets/           彩蛋页原图
```

## 免责与风险

- **刷写固件属高风险操作**，可能导致设备损坏或无法恢复官方状态，请自行承担后果。
- 刷写后可写入非业余无线电频率，请确保已取得当地主管部门的使用许可。
- 本软件按现状提供，维护者不对适用性、持续可用性或与特定设备的兼容性作任何保证。

## 许可

本项目中由维护者拥有或有权授权的原创内容，适用 **PolyForm Noncommercial License 1.0.0**（详见 [LICENSE](LICENSE)），
**禁止用于任何商业用途**（含收费销售、付费下载、收费分发等）。

维护者标识：GitHub 账户 **@Medic-furry**
项目地址：https://github.com/Medic-furry/Mi-Walkie-Talkie-Offline

第三方软件（含原厂应用）的名称、商标、原始代码与素材，权利归各自权利人所有，本许可不涉及上述内容。
