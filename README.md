# 小米对讲机 APK 免登录本地化修改版

该说明中的技术分析、逆向解释及部分实现说明由开发过程中使用的 AI 辅助生成。开发使用 WorkBuddy 的专家模型。由于逆向工程、Smali 修改与 Android 运行时行为存在复杂性，本文内容不保证绝对准确，部分推断可能存在偏差，请自行验证。

> **基于 GitHub 用户 *Darkhorse* 的项目：** [Mi-Walkie-Talkie-Plus](https://github.com/Mi-Walkie-Talkie-by-Darkhorse/Mi-Walkie-Talkie-Plus)（分支 `2.13.7-plus`）  
> **修改目标：**  
> - 完全免除 APP 登录（支持手机号/微信/小米账号三种方式），实现离线本地化运行 但APP可能仍会尝试连接官方服务器（但无登录凭证，不影响本地使用）；若希望完全离线，可阻止 APP 联网权限 
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
- 固件版本读取  
- 固件更新错误分支  

---

## 项目特点

- 无需登录（跳过手机号/微信/小米账号全部登录方式）  
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
| 基础版本   | 2.13.7 (Darkhorse Plus)                |
| 包名     | `com.ifengyu.intercom` |
| 修改日期   | 2026-05-13           |
| 当前版本   | V.01-BATE（第一版）                 |
| 修改方式   | Smali Patch（apktool 反编译后直接修改字节码）           |

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
| 新增   | - `fwdir()` 本地固件目录检测（全新方法）<br>- `Ju()` Shark 硬件版本包装方法（全新方法）<br>- 4 个版本检测方法中的 `fwdir()` 前置检查<br>- 2 个固件回调中的本地模型构造 |
| 修改   | - 初始化检查（`A()` 始终返回 true）<br>- 登录过期验证（`d()` 直接返回）<br>- userid 获取（`N()` 固定返回 "1"）<br>- 版本检测方法 `D()` `Ju()` `K()` `l()` 增加 `fwdir()` 前置检查 |
| 未修改 | - BLE 协议<br>- MCU 更新流程<br>- 固件传输协议<br>- 音频逻辑 |

---

## 技术实现

> 以下每处修改均与原始 GitHub 源码（`Mi-Walkie-Talkie-Plus` 分支 `2.13.7-plus`）逐行核对。

总共修改了 **4 个文件**，涉及 **9 处代码逻辑**（本次分析从原始 5 处更新为 9 处，详见底部新增部分）。具体如下：

---

### 修改1：绕过 userid 初始化检查（`MiTalkiApp.smali` A() 方法）

**路径：** `smali/com/ifengyu/intercom/MiTalkiApp.smali`

**原始 GitHub 源码（`MiTalkiApp.java` 第 202-204 行）：**
```java
private boolean A() {
    return !com.ifengyu.library.a.l.b(this.o);  // 检查 userid 是否为空
}
```
`this.o` 来自 `d0.N()`，即从 `SharedPreferences("sp_user")` 读取的 `userid`。无论通过手机号、微信还是小米账号登录，只要 `userid` 非空即可通过检查。

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

**影响链：** `onCreate()` → `q()` → `j()` → `A()` → 始终继续初始化（`x()` `u()` `y()` `E()` `D()` 全部执行）

---

### 修改2：跳过登录过期检查（`MiTalkiApp.smali` d() 方法）

**路径：** `smali/com/ifengyu/intercom/MiTalkiApp.smali`

**原始 GitHub 源码（`MiTalkiApp.java` 第 441-462 行）：**
```java
protected void d() {
    int i = d0.R().getInt("user_last_login_time", 0);
    if (i == 0) {
        i = (int) (System.currentTimeMillis() / 1000);
        d0.R().edit().putInt("user_last_login_time", i).apply();
    }
    int i2 = d0.R().getInt("user_expires_in", 0);
    if (i2 == 0) {
        i2 = 7776000;
        d0.R().edit().putInt("user_expires_in", 7776000).apply();
    }
    if (((int) (System.currentTimeMillis() / 1000)) - i >= i2) {
        w.b().b(...);  // 弹出重新登录对话框
    } else {
        com.ifengyu.intercom.g.a.c(d0.R().getString("userid", ""), new c());
    }
}
```
该方法在每次 Activity 启动（除 SplashActivity 和 WebViewActivity）时被调用，通过 `user_last_login_time` 和 `user_expires_in` 判断 session 是否过期。过期则弹窗强制重新登录，未过期则调服务器 API 验证用户有效性。

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

**原始 GitHub 源码（`d0.java` 第 90-92 行）：**
```java
public static String N() {
    return R().getString(AuthorizeActivityBase.KEY_USERID, "");
}
```

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

### 修改4：新增固件目录检测方法（`d0.smali` fwdir() 方法）

**路径：** `smali_classes2/com/ifengyu/intercom/i/d0.smali`

**原始 GitHub 源码：** `fwdir()` 在原始 `d0.java` 中**不存在**，为全新增方法。

**需求：** 需要通过此方法检测 `/sdcard/Mi-walkie-talkie.firmware/` 本地固件目录是否存在，供固件版本检测和固件更新回调使用。

**实现：** 新增 `public static fwdir()Z` 方法，检查 SD 卡挂载状态后判断目录是否存在。声明为 `public` 以允许 `g/d/i.smali` 和 `g/d/j.smali` 跨类调用。

**Patch（完整实现）：**
```smali
.method public static fwdir()Z
    .locals 3

    :try_start_0
    invoke-static {}, Landroid/os/Environment;->getExternalStorageState()Ljava/lang/String;
    move-result-object v0
    const-string v1, "mounted"
    invoke-virtual {v1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v1
    if-nez v1, :cond_0
    const-string v1, "mounted_ro"
    invoke-virtual {v1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v0
    if-eqz v0, :cond_1
    :cond_0
    new-instance v0, Ljava/io/File;
    invoke-static {}, Landroid/os/Environment;->getExternalStorageDirectory()Ljava/io/File;
    move-result-object v1
    const-string v2, "Mi-walkie-talkie.firmware"
    invoke-direct {v0, v1, v2}, Ljava/io/File;-><init>(Ljava/io/File;Ljava/lang/String;)V
    invoke-virtual {v0}, Ljava/io/File;->isDirectory()Z
    move-result v0
    :cond_1
    return v0
    :catch_0
    const/4 v0, 0x0
    return v0
.end method
```

---

### 修改5：增加本地固件回退机制（`i.smali` / `j.smali`）

**路径：**  
- `smali_classes2/com/ifengyu/intercom/g/d/i.smali`（SealUpdateInfoCallback）  
- `smali_classes2/com/ifengyu/intercom/g/d/j.smali`（SharkUpdateInfoCallback）  

**原始 GitHub 源码（`g/d/i.java` 第 14-22 行 / `g/d/j.java` 第 14-22 行）：**
```java
public McuUpdateInfoModel a(Response response, int i) throws Exception {
    String string = response.body().string();
    JSONObject jSONObject = new JSONObject(string);
    if (jSONObject.getInt("errno") != 0) {
        return null;    // 服务器返回错误 → 直接退出
    }
    // i.java: JSON key = "seal"   /   j.java: JSON key = "shark"
    return new Gson().fromJson(jSONObject.getJSONObject("data")
        .getJSONObject("seal").toString(), McuUpdateInfoModel.class);
}
```

**原逻辑：** 服务器返回 `errno != 0` 时，返回 `null` 并退出固件更新流程，导致本地固件无法使用。

**修改后：** 当本地固件文件夹存在且服务器返回错误时，构造一个本地 `McuUpdateInfoModel` 实例（设定 `versionCode="99"`、`versionName="Local"`、`source` 指向本地固件文件名），返回给回调以触发固件更新流程，从而使用本地固件。

**Patch（i.smali — Seal，固件文件 `seal_firmware.bin`）：**
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

**Patch（j.smali — Shark，固件文件 `shark_firmware.bin`）：** 同上，`source` 改为 `"shark_firmware.bin"`。

---

### 修改6：绕过 Seal 软件版本读取（`d0.smali` D() 方法）

**路径：** `smali_classes2/com/ifengyu/intercom/i/d0.smali`

**原始 GitHub 源码（`d0.java` 第 47-49 行）：**
```java
public static int D() {
    return A().getInt("seal_version_soft", 0);
}
```
直接从 `SharedPreferences("sp_seal")` 读取 `seal_version_soft`，无任何前置检查。

**原逻辑：** 直接返回已存储的 Seal（海豹）对讲机软件版本号。

**问题：** 当本地固件目录存在时，应忽略已存储的版本号以触发固件更新。

**修改后：** 优先检查 `fwdir()`；若本地固件目录存在，跳过 SharedPreferences 读取，返回默认值 `0`（使 APP 认为当前无固件需要更新）。

**Patch：**
```smali
.method public static D()I
    .locals 3

    invoke-static {}, Lcom/ifengyu/intercom/i/d0;->fwdir()Z    # 新增

    move-result v0
    if-nez v0, :cond_0                                         # 新增

    invoke-static {}, Lcom/ifengyu/intercom/i/d0;->A()Landroid/content/SharedPreferences;
    move-result-object v0
    const-string v1, "seal_version_soft"
    const/4 v2, 0x0
    invoke-interface {v0, v1, v2}, Landroid/content/SharedPreferences;->getInt(Ljava/lang/String;I)I
    move-result v0

    :cond_0
    return v0
.end method
```

---

### 修改7：新增 Shark 硬件版本包装方法（`d0.smali` Ju() 方法）

**路径：** `smali_classes2/com/ifengyu/intercom/i/d0.smali`

**原始 GitHub 源码：** `Ju()` 在原始 `d0.java` 中**不存在**，为全新增方法。原始仅有 `J()` 方法直接返回 `I().getInt("shark_version_hw", 0)`。

**需求：** 保留原始 `J()` 不变（避免破坏其他调用方），新增包装方法 `Ju()` 在调用 `J()` 前增加 `fwdir()` 检查。

**实现：** 当 `fwdir()` 返回 true 时，直接返回 `2`（大于正常版本号，强制触发固件更新）；否则委托给原有 `J()` 方法。

**Patch（完整新增）：**
```smali
.method public static Ju()I
    .locals 1

    invoke-static {}, Lcom/ifengyu/intercom/i/d0;->fwdir()Z

    move-result v0
    if-nez v0, :cond_0
    invoke-static {}, Lcom/ifengyu/intercom/i/d0;->J()I
    move-result v0
    return v0

    :cond_0
    const/4 v0, 0x2
    return v0
.end method
```

---

### 修改8：绕过 Shark 软件版本读取（`d0.smali` K() 方法）

**路径：** `smali_classes2/com/ifengyu/intercom/i/d0.smali`

**原始 GitHub 源码（`d0.java` 第 78-80 行）：**
```java
public static int K() {
    return I().getInt("shark_version_soft", 0);
}
```
直接从 `SharedPreferences("sp_shark")` 读取 `shark_version_soft`，无任何前置检查。

**原逻辑：** 直接返回已存储的 Shark（鲨鱼）对讲机软件版本号。

**修改后：** 优先检查 `fwdir()`；若本地固件目录存在，跳过 SharedPreferences 读取，返回默认值 `0`。

**Patch：**
```smali
.method public static K()I
    .locals 3

    invoke-static {}, Lcom/ifengyu/intercom/i/d0;->fwdir()Z    # 新增

    move-result v0
    if-nez v0, :cond_0                                         # 新增

    invoke-static {}, Lcom/ifengyu/intercom/i/d0;->I()Landroid/content/SharedPreferences;
    move-result-object v0
    const-string v1, "shark_version_soft"
    const/4 v2, 0x0
    invoke-interface {v0, v1, v2}, Landroid/content/SharedPreferences;->getInt(Ljava/lang/String;I)I
    move-result v0

    :cond_0
    return v0
.end method
```

---

### 修改9：绕过 MCU 版本读取（`d0.smali` l() 方法）

**路径：** `smali_classes2/com/ifengyu/intercom/i/d0.smali`

**原始 GitHub 源码（`d0.java` 第 247-249 行）：**
```java
public static int l() {
    return j().getInt("versionMCU", -1);
}
```
直接从 `SharedPreferences("sp_mitalki")` 读取 `versionMCU`，默认值 `-1`，无任何前置检查。

**原逻辑：** 直接返回已存储的 MCU 固件版本号。

**修改后：** 优先检查 `fwdir()`；若本地固件目录存在，跳过 SharedPreferences 读取，返回默认值 `0`。

**Patch：**
```smali
.method public static l()I
    .locals 3

    invoke-static {}, Lcom/ifengyu/intercom/i/d0;->fwdir()Z    # 新增

    move-result v0
    if-nez v0, :cond_0                                         # 新增

    invoke-static {}, Lcom/ifengyu/intercom/i/d0;->j()Landroid/content/SharedPreferences;
    move-result-object v0
    const-string v1, "versionMCU"
    const/4 v2, -0x1
    invoke-interface {v0, v1, v2}, Landroid/content/SharedPreferences;->getInt(Ljava/lang/String;I)I
    move-result v0

    :cond_0
    return v0
.end method
```

---

## 原始 GitHub 源码逐行对照表

以下为本文档 9 处修改与 Darkhorse `2.13.7-plus` 分支原始源码的逐行对照：

| 编号 | 文件 | 方法 | 原始源码行号 | 原始逻辑 | 修改 |
|------|------|------|-------------|--------|------|
| 1 | `MiTalkiApp.java` → `MiTalkiApp.smali` | `A()` | L202-204 | `return !library.a.l.b(this.o)` — 检查 userid 非空 | 始终返回 true |
| 2 | `MiTalkiApp.java` → `MiTalkiApp.smali` | `d()` | L441-462 | 检查 session 过期+调服务器验证 → 弹窗或调用 API | 直接 return-void |
| 3 | `d0.java` → `d0.smali` | `N()` | L90-92 | `return R().getString("userid", "")` | 固定返回 "1" |
| 4 | `d0.java` → `d0.smali` | `fwdir()` | **不存在** | — | **全新增**：检测 `/sdcard/Mi-walkie-talkie.firmware/` |
| 5-A | `g/d/i.java` → `g/d/i.smali` | `a()` | L14-22 | `errno != 0 → return null` | 增加 fwdir() 本地回退，固件=seal_firmware.bin |
| 5-B | `g/d/j.java` → `g/d/j.smali` | `a()` | L14-22 | `errno != 0 → return null` | 增加 fwdir() 本地回退，固件=shark_firmware.bin |
| 6 | `d0.java` → `d0.smali` | `D()` | L47-49 | `A().getInt("seal_version_soft", 0)` | 增加 fwdir() 前置检查 |
| 7 | `d0.java` → `d0.smali` | `Ju()` | **不存在** | 原始仅有 `J()` (= `I().getInt("shark_version_hw", 0)`) | **全新增**：包装 J()，fwdir() 存在时返回 2 |
| 8 | `d0.java` → `d0.smali` | `K()` | L78-80 | `I().getInt("shark_version_soft", 0)` | 增加 fwdir() 前置检查 |
| 9 | `d0.java` → `d0.smali` | `l()` | L247-249 | `j().getInt("versionMCU", -1)` | 增加 fwdir() 前置检查 |

---

## 工作原理流程

```text
安装 APK
 │
 ├─ 1. APP 启动 → MiTalkiApp.onCreate()
 │     ├─ A() 返回 true （绕过 userid 检查，修改1）
 │     ├─ d() 直接返回 （跳过登录过期检查，修改2）
 │     ├─ N() 返回 "1" （提供固定 userid，修改3）
 │     └─ APP 正常初始化 （无需任何登录、无需联网）
 │
 ├─ 2. 手动创建文件夹： 
 │       /sdcard/Mi-walkie-talkie.firmware/
 │       └─ 放入对应型号固件文件
 │
 ├─ 3. fwdir() 检测到文件夹存在（修改4）
 │     ├─ D() → 返回 0（跳过 Seal 已存版本，修改6）
 │     ├─ Ju() → 返回 2（强制 Shark 硬件更新信号，修改7）
 │     ├─ K() → 返回 0（跳过 Shark 已存版本，修改8）
 │     └─ l() → 返回 0（跳过 MCU 已存版本，修改9）
 │
 ├─ 4. APP 向服务器请求更新信息
 │     └─ 服务器返回 errno ≠ 0（无登录凭证）
 │
 ├─ 5. 回调进入错误分支（修改5-A / 5-B）
 │     ├─ fwdir() == true
 │     ├─ 创建本地 McuUpdateInfoModel
 │     │    ├─ versionCode = "99"
 │     │    ├─ versionName = "Local"
 │     │    └─ source = 对应 .bin 文件名
 │     └─ 返回模型 → 触发固件更新流程
 │
 └─ 6. 固件刷入完成后 
       ⚠️ 务必删除或重命名 Mi-walkie-talkie.firmware 文件夹，否则误点升级会再次刷机
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
java -jar apktool.jar b V.01-BATE -o output-unsigned.apk

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
├── V.01-BATE/
│
├── smali/
│   └── com/ifengyu/intercom/
│       └── MiTalkiApp.smali          # ★ 修改1：A() / 修改2：d()
│
├── smali_classes2/
│   └── com/ifengyu/intercom/
│       ├── i/
│       │   └── d0.smali              # ★ 修改3-9：N()/fwdir()/D()/Ju()/K()/l()
│       │
│       └── g/d/
│           ├── i.smali               # ★ 修改5-A：Seal 固件回退
│           └── j.smali               # ★ 修改5-B：Shark 固件回退
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
