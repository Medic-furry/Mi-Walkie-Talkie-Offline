# 小米对讲机 APK 免登录本地化修改版

该说明中的技术分析、逆向解释及部分实现说明由开发过程中使用的 AI 辅助生成。开发使用 WorkBuddyd 的专家模型。由于逆向工程、Smali 修改与 Android 运行时行为存在复杂性，本文内容不保证绝对准确，部分推断可能存在偏差，请自行验证。

> **基于 GitHub 用户 *Darkhorse* 的项目：** [Mi-Walkie-Talkie-Plus](https://github.com/Mi-Walkie-Talkie-by-Darkhorse/Mi-Walkie-Talkie-Plus)  
> **修改目标：**  
> - 完全免除 APP 登录，实现离线本地化运行 但APP可能仍会尝试连接官方服务器（但无登录凭证，不影响本地使用）；若希望完全离线，可阻止 APP 联网权限 
> - 保留原版 BLE/MCU/固件升级逻辑  
> - 支持通过本地文件夹 `/sdcard/Mi-walkie-talkie.firmware/` 刷入自定义固件  
> - 尽可能减少对原版代码结构的侵入式修改  

---

## 重要风险警告

由于本 APK 修改了联网认证相关逻辑：  

- 回滚官方固件可能仍需要使用 Darkhorse 项目的 APK  
- 小米/蜂语 官方目前并未公开提供固件下载  
- 小米/蜂语 官方正在逐步关闭旧版 APP 登录认证体系  
- 新注册账号目前通常已经无法登录  
- 部分旧账号未来也可能失效  

因此：  

- 刷入错误固件可能导致设备损坏  
- 后续可能无法恢复官方状态  
- 部分设备未来可能无法再回滚官方固件  

请务必确认：  

- 固件型号正确  
- 设备型号匹配  
- 电量充足  
- 文件来源可信  

**刷机有风险，请谨慎操作。**

---

## 免责声明

本项目仅用于：

- 逆向工程研究  
- 固件研究  
- Android 修改研究  
- 学习与技术交流  

本项目中的商标、图标、应用资源及原始代码均归原作者及相关公司所有。开发者不提供任何形式的保证，也不对以下情况承担责任：

- 设备损坏  
- 固件损坏  
- 数据丢失  
- 对讲机异常  
- 刷机失败  
- 使用本项目导致的法律风险  
- 使用本项目产生的任何后果  

请自行承担使用风险。

---

## 执行摘要

- 本项目移除了原版 APP 的登录依赖，实现了离线本地化运行。  
- 支持通过 `/sdcard/Mi-walkie-talkie.firmware/` 文件夹刷入自定义固件。  
- 保留原版 BLE 通信和固件升级逻辑，尽量不破坏原版代码结构。  

---

## 项目概述

原版小米米家对讲机 APP 在启动阶段依赖在线账号状态。若未登录：

- APP 初始化会中断  
- 固件模块不会注册  
- 部分蓝牙功能无法继续  
- 固件更新流程不会进入  

本项目目标并不是修改底层通信协议，而是：在尽可能保留原版结构与逻辑的前提下，移除在线登录依赖，使 APP 可以完全离线运行。  

同时保留：

- 原版 BLE 通信逻辑  
- 原版 MCU 更新逻辑  
- 原版固件刷写流程  
- 原版固件更新回调结构  

仅对以下内容进行最小化修改：

- 初始化检查  
- 登录状态验证  
- 固件更新错误分支  

---

## 项目特点

- 无需微信登录  
- 无需在线认证  
- 支持离线运行  
- 保留原版固件升级逻辑  
- 保留原版 BLE 协议  
- 保留原版 MCU 流程  
- 支持本地固件文件夹  
- 最小侵入式 Smali 修改  

---

## 版本信息

| 项目     | 内容                   |
| ------ | -------------------- |
| 基础版本   | 2.13.7                |
| 包名     | `com.ifengyu.intercom` |
| 修改日期   | 2026-05-13           |
| 当前版本   | 第一版                 |
| 修改方式   | Smali Patch           |

---

## 修改策略

本项目核心策略：  

- 保留原版 BLE/MCU/固件升级流程  
- 最小化修改 APP 主体结构  
- 仅移除在线认证依赖  
- 不修改底层刷机协议  
- 尽可能兼容原版升级逻辑  
- 通过原版回调注入本地固件模型  

本项目不修改：BLE 通信协议、固件传输逻辑、MCU 刷写逻辑、音频通信逻辑、射频相关逻辑。

---

## 差异总结

| 类别   | 说明                                   |
| ----- | ------------------------------------ |
| 新增   | - 本地固件回退 (local firmware fallback)<br>- 离线初始化支持<br>- 本地固件目录检测 |
| 修改   | - 初始化检查<br>- 登录状态验证<br>- 固件更新错误分支        |
| 未修改 | - BLE 协议<br>- MCU 更新流程<br>- 固件传输协议<br>- 音频逻辑 |

---

## 技术实现

总共修改了 4 个文件，涉及 5 处代码逻辑。具体如下：

### 修改1：绕过 userid 初始化检查（`MiTalkiApp.smali` A() 方法）

**路径：** `smali/com/ifengyu/intercom/MiTalkiApp.smali`

**原逻辑：** 启动阶段读取 `userid`，若为空则阻止初始化（初始化中断，固件模块不注册）。  

**修改后：** 始终返回 `true`，允许 APP 在无登录状态下完成初始化。  

**Patch：**

```smali
.method private A()Z
    .locals 1
    const/4 v0, 0x1
    return v0
.end method
```

---

### 修改2：跳过登录过期检查（`MiTalkiApp.smali` d() 方法）

**路径：** `smali/com/ifengyu/intercom/MiTalkiApp.smali`

**原逻辑：** 检查登录 session 过期状态，若失效则弹出重新登录窗口并中断流程。  

**修改后：** 直接返回，跳过所有登录状态验证。  

**Patch：**

```smali
.method protected d()V
    .locals 0
    return-void
.end method
```

---

### 修改3：提供固定 userid（`d0.smali` N() 方法）

**路径：** `smali_classes2/com/ifengyu/intercom/i/d0.smali`

**原逻辑：** 从 SharedPreferences 读取 `userid`，默认值为空字符串。  

**问题：** 部分服务器接口要求 `userid` 为整数，否则返回 `userid must be integer` 错误，导致固件回调无法继续执行。  

**修改后：** 始终返回固定 `userid = "1"`，满足服务器整数校验要求。  

**Patch：**

```smali
.method public static N()Ljava/lang/String;
    .locals 1
    const-string v0, "1"
    return-object v0
.end method
```

---

### 修改4：允许跨类访问固件目录检测方法（`d0.smali` fwdir() 方法）

**路径：** `smali_classes2/com/ifengyu/intercom/i/d0.smali`

**原逻辑：** `fwdir()` 方法为 `private static`，仅当前类内部可调用。  

**需求：** 固件更新回调位于 `g/d/i.smali` 和 `g/d/j.smali`，需要调用 `fwdir()` 检测 `/sdcard/Mi-walkie-talkie.firmware/` 目录是否存在。  

**修改后：** 修改 `fwdir()` 为 `public static`，允许其他类调用，逻辑保持不变。  

**Patch：**

```smali
.method public static fwdir()Z
    .locals 3
    ...（实现逻辑保持不变）...
.end method
```

---

### 修改5：增加本地固件回退机制（`i.smali` / `j.smali`）

**路径：**  
- `smali_classes2/com/ifengyu/intercom/g/d/i.smali`  
- `smali_classes2/com/ifengyu/intercom/g/d/j.smali`  

**原逻辑：** 服务器返回 `errno != 0` 时，返回 `null` 并退出固件更新流程，导致本地固件无法使用。  

**修改后：** 当本地固件文件夹存在且服务器返回错误时，构造一个本地 `McuUpdateInfoModel` 实例（设定 `versionCode="99"`、`versionName="Local"`、`source` 指向本地固件文件名），返回给回调以触发固件更新流程，从而使用本地固件。  

**Patch：**

```smali
:cond_0
invoke-static {}, Lcom/ifengyu/intercom/i/d0;->fwdir()Z
move-result p1
if-eqz p1, :cond_null

new-instance p1, Lcom/ifengyu/intercom/bean/McuUpdateInfoModel;
invoke-direct {p1}, Lcom/ifengyu/intercom/bean/McuUpdateInfoModel;-><init>()V

const-string p2, "99"
invoke-virtual {p1, p2}, Lcom/ifengyu/intercom/bean/McuUpdateInfoModel;->setVersionCode(Ljava/lang/String;)V

const-string p2, "Local"
invoke-virtual {p1, p2}, Lcom/ifengyu/intercom/bean/McuUpdateInfoModel;->setVersionName(Ljava/lang/String;)V

const-string p2, "seal_firmware.bin"
invoke-virtual {p1, p2}, Lcom/ifengyu/intercom/bean/McuUpdateInfoModel;->setSource(Ljava/lang/String;)V

return-object p1

:cond_null
const/4 p1, 0x0
return-object p1
```

---

## 工作原理流程

```text
安装 APK
 │
 ├─ 1. APP 启动 → MiTalkiApp.onCreate()
 │     ├─ A() 返回 true （绕过 userid 检查）
 │     ├─ d() 直接返回 （跳过登录过期检查）
 │     └─ APP 正常初始化 （无需任何登录、无需联网）
 │
 ├─ 2. 手动创建文件夹： 
 │       `/sdcard/Mi-walkie-talkie.firmware/`
 │       └─ 从 GitHub 项目下载对应型号的固件文件，放入此目录
 │
 ├─ 3. APP 检测固件版本
 │     ├─ fwdir() 检测到文件夹存在
 │     └─ 向服务器请求更新信息
 │
 ├─ 4. 服务器返回错误（errno != 0）
 │     └─ 回调进入错误分支
 │
 ├─ 5. 回调中发现 fwdir() == true
 │     ├─ 创建本地 McuUpdateInfoModel
 │     │    ├─ versionCode = "99"
 │     │    ├─ versionName = "Local"
 │     │    └─ source = 对应的 .bin 文件名
 │     └─ 返回模型 → 触发固件更新流程
 │
 └─ 6. 固件刷入完成后 
       ⚠️ **务必删除或重命名 `Mi-walkie-talkie.firmware` 文件夹，否则误点升级会再次进行刷机。**
```

---

## 固件目录

本地固件目录：  
```
/sdcard/Mi-walkie-talkie.firmware/
```  
固件文件需要放置于该目录。固件文件名仍遵循原版项目中的定义和固件模型。  

---

## 使用说明

### 安装步骤

1. 安装 APK  
2. 首次启动时授予：定位权限、蓝牙权限、文件访问权限  
3. 创建目录：  
   ```text
   /sdcard/Mi-walkie-talkie.firmware/
   ```  
4. 从 [Mi-Walkie-Talkie-Plus](https://github.com/Mi-Walkie-Talkie-by-Darkhorse/Mi-Walkie-Talkie-Plus) 项目中下载对应型号的固件文件，并放入该文件夹  
5. 启动 APP  
6. APP 自动检测固件并提示更新  
7. 固件刷入完成后：⚠️ **务必删除或重命名 `Mi-walkie-talkie.firmware` 文件夹，否则误点升级会再次进行刷机。**  

---

## 回滚官方固件

目前回滚官方固件仍依赖 GitHub 用户 Darkhorse 项目的原版 APK。原因：  

- 小米/蜂语 官方正在逐步关闭旧版 APP 登录认证体系  
- 新注册账号目前通常已经无法登录  
- 部分旧账号未来也可能失效  

由于官方目前并未公开提供固件下载：  

- 一旦刷入错误固件  
- 或未来旧登录体系彻底失效  

则设备可能无法恢复官方状态。  

目前已知仍可用于回滚的项目：  

- Darkhorse 的原版项目：[Mi-Walkie-Talkie-Plus](https://github.com/Mi-Walkie-Talkie-by-Darkhorse/Mi-Walkie-Talkie-Plus)  

---

## 构建指南

### 环境要求

- Java JDK 17+  
- Apktool 2.4.0+  
- Android SDK Build Tools 36.0.0+（包含 `zipalign` 和 `apksigner`）  

### 构建步骤

```bash
# 1. 重新打包
java -jar apktool.jar b v15_decompiled -o output-unsigned.apk

# 2. 4字节对齐
zipalign -p 4 output-unsigned.apk output-aligned.apk

# 3. 签名（使用 debug.keystore）
apksigner sign --ks debug.keystore \
  --ks-key-alias androiddebugkey \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out MI-Walkie-Talkie-final.apk \
  output-aligned.apk

# 4. 验证签名
apksigner verify --verbose MI-Walkie-Talkie-final.apk
```

---

## 文件结构

```text
项目目录/
├── README.md
├── apktool.jar
├── v15_decompiled/
│
├── smali/
│   └── com/ifengyu/intercom/
│       └── MiTalkiApp.smali
│
├── smali_classes2/
│   └── com/ifengyu/intercom/
│       ├── i/
│       │   └── d0.smali
│       │
│       └── g/d/
│           ├── i.smali
│           └── j.smali
│
├── AndroidManifest.xml
├── apktool.yml
├── assets/
├── res/
└── lib/
```



---

## 致谢

- Darkhorse 的原始项目： [Mi-Walkie-Talkie-Plus](https://github.com/Mi-Walkie-Talkie-by-Darkhorse/Mi-Walkie-Talkie-Plus)  
- XDA Reverse Engineering 讨论：[Hack Xiaomi Mijia Walkie Talkie](https://xdaforums.com/t/hack-xiaomi-mijia-walkie-talkie.3840164/)  

---

## 许可协议

本项目仅包含：修改逻辑、Smali Patch、逆向分析、构建说明。原始 APP 的资源、图标、商标、原始代码均归原作者及相关公司所有。
