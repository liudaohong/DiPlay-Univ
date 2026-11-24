# DiPlay-Univ（长安 UNI-V CarPlay 定制增强版）

面向 **长安 UNI-V（2022 款 / 飞鱼 OS / Android 9 / 联发科车机平台）** 的开源 **无线 / 有线 CarPlay 接收端**。

- **仓库地址**：[https://github.com/liudaohong/DiPlay-Univ](https://github.com/liudaohong/DiPlay-Univ)
- **原始上游项目**：[shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay)（GPL-3.0，已按阶段精准移植 `v0.2.12 → v0.2.22 → main` 核心音视频、工具、连接与安全优化）
- **开发与安全加固计划**：[plan/UPSTREAM_INTEGRATION_PLAN.md](plan/UPSTREAM_INTEGRATION_PLAN.md)

> [!WARNING]
> **仅供个人研究与非商业用途，不对实车使用引发的兼容性或驾驶风险负责。**  
> 本仓库不含任何私有签名密钥、离线 MFi 认证资产或预编译 APK；上车使用的完整安装包需按下方「本地构建」章节自行配置认证资产与私有签名编译。

---

## 一、适用环境与版本信息

- **当前版本**：`0.2.30`（`versionCode 45`，包名 `com.shihab.diplay.hudtest`）
- **系统支持**：Android 7.1+（`minSdk 25`，`compileSdk 37`），支持 `arm64-v8a` / `armeabi-v7a` / `x86_64`
- **已重点适配车型**：长安 UNI-V 2022 款（飞鱼 OS / Android 9 / MediaTek 芯片，支持免高德三指飞屏仪表投屏）；同时可直接安装至普通 Android 手机（Android 7.1–16）配合 iPhone 预演无线 CarPlay 与模拟副屏主题
- **无线连接方式**：车机热点（2.4GHz / 5GHz 均可）、Wi-Fi Direct（兼容 Android 9 旧路径）、现有 Wi-Fi / 同局域网、以及有线 USB（NCM）

---

## 二、核心功能与亮点

### 1. 长安 UNI-V 专属能力
- **免高德三指飞屏仪表投屏**：三指左滑/右滑在「原车仪表 ↔ 整机镜像 ↔ 仅导航 ↔ 取景窗」之间循环切换，无需原车高德在后台运行；支持冷启动屏幕就绪自动补投。
- **不遮挡原车行车仪表**：投屏运行于原车高德飞屏 Android 副屏层，处于原车硬件 MCU 图层之下，原车车速、挡位、转速、故障警告灯始终由 MCU 硬件置顶显示；底部默认预留 `110 px` 安全区避让仪表饰板。
- **取景窗与自定义主题引擎**：`1920×720` 画布，支持 PNG/GIF、专辑封面 `@cover`、渐变、矢量 `vector`、16 段音乐频谱 `spectrum`、车速/转速数据绑定、状态机与规则动画；支持五张仪表卡片（转向/到达/车道/路名/歌曲）独立 X/Y 坐标与 `60%~160%` 缩放调节、仪表画面裁剪与二指切主题。
- **原车硬件联动**：
  - 实时车况与大灯读取（`com.wt.n2048`）：支持「跟随车灯状态」自动切换 CarPlay 日/夜模式；
  - 原车氛围灯音乐律动：传递音乐 `AudioTrack` 会话至车机律动服务；
  - 自动开关蓝牙 & 方向盘键：连接前自动开蓝牙、主屏就绪延时关蓝牙、断连/熄火自动恢复蓝牙；支持方向盘语音键短按屏蔽 Siri（避免与小安语音同时唤醒）。
- **长安专属网络调优**：放行 2.4GHz 车机热点、热点地址 IPv4 优先、主动下发 Wi-Fi 配置（`0x5703`）、Android 9 `Os.dup` ~12 秒 TCP 半开检测与指数退避重连。

### 2. 上游核心优化合入（视频、音频、工具与连接）
- **视频解码与触控**：可选 **MediaTek（联发科）低延迟解码**（带异常自动回退）、视频解码队列减分配与网络抖动积压恢复、HEVC 无硬解自动回退 H.264、倒车影像退出后画幅恢复、触控通道 `TCP_NODELAY` 与慢连接拖拽合并。
- **音频与通话升级**：集成原生 JNI **SpeexDSP 通话回声消除（`libspeex_echo.so`）与 200Hz 低频语音滤波**、音乐丢包平滑填充（`LostMusicFill`）、音频线程高优先级、独立媒体/导航声道与缓冲调节、可选「纯投屏 + 原车蓝牙音频」模式（与自动关蓝牙互斥）。
- **免文件选择器效率工具**：针对车机缺少文件管理器痛点，内置 **手机扫码备份 / 恢复全部设置**（完整涵盖长安仪表五卡与主题配置）、**手机扫码下载脱敏诊断报告**、以及 **设置页内联中英文搜索**。
- **连接诊断与稳定性**：首页与等待页清晰展示连接失败原因、自动记录崩溃堆栈并写入诊断报告、有线 USB 重插容错与损坏 NCM 包不断连、可选 Wi-Fi Direct 期间暂停后台扫描（ADB）。

### 3. 纵深安全加固
- **私有 Release 签名门禁**：内置 `assemblePrivateRelease` 与 `-PrequirePrivateSigning=true` 校验，支持使用个人专属 `4096-bit RSA` 签名密钥替代公开 debug 签名，防同包名 APK 覆盖劫持。
- **本地凭据硬件加密**：基于 Android Keystore（`AES-256-GCM`）对本地 ADB 私钥、AirPlay/Lockdown 长期私钥、热点/Wi-Fi 密码与远程 MFi Token 加密落盘，支持旧版明文配置无缝原地迁移与瞬时故障防丢配对保护。
- **协议与局域网防护**：
  - AirPlay 控制通道强制校验 `PairVerify` 身份认证后才放行 `SETUP`/`RECORD`/`TEARDOWN`/`/command`/`/feedback`，并提供可选 **「锁定已配对 iPhone（拒绝陌生设备配对）」** 开关；
  - `ControlFrameBuffer`、RTSP、Bplist、TLV8 报文长度与缓冲区上限防护；
  - 扫码备份/日志 HTTP 服务采用 `128-bit` 随机路径 Token（常量时间比对）、按源 IP 独立限流、仅放行私网/回环地址与 `POST` 同源 CSRF 校验。
- **组件暴露面最小化**：Release 包自动移除调试广播 `BenchClusterReceiver` 与 Android Auto 服务 `MyCarAppService`；Android 9 车机默认禁用 `MapEmbedService` 导出服务；广播接收器兼顾飞鱼 OS STR 休眠唤醒与行车中防抢占冷却保护；自定义主题解析施加体积/数量配额并支持「一键导入应用私有安全目录」。

---

## 三、本地构建指南（开发者）

### 1. 构建环境要求
- **JDK**：JDK 21
- **Android SDK**：`compileSdk 37`（`minSdk 25`）
- **Android NDK**：`28.0.12433566`（用于编译 `libspeex_echo.so`、`libxcertplay_i2c.so`、`liblocal_hotspot_radio.so`）

### 2. 源码构建（不含 MFi 认证资产）
```powershell
.\gradlew.bat :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:assembleDebug --no-daemon
```

### 3. 上车用私有签名 Release 构建（推荐）
将离线 MFi 资产目录与私有签名密钥目录**分别放在本仓库之外的独立目录**（严禁把 `.jks` 放在 `DIPLAY_AUTH_ASSETS_DIR` 内，构建任务 `rejectBundledCredentials` 会自动拦截误打包凭据）：

```powershell
# 1. 指向离线 MFi 资产目录（内含 offline-mfi/certificate.p7b 与 offline-mfi/identity.pk8）
$env:DIPLAY_AUTH_ASSETS_DIR    = "D:\path\to\diplay-auth-assets"

# 2. 指向仓库外私有 Release Keystore
$env:ANDROID_KEYSTORE_PATH     = "D:\path\to\diplay-signing-keystore\release.jks"
$env:ANDROID_KEYSTORE_PASSWORD = "<your-store-password>"
$env:ANDROID_KEY_ALIAS         = "<your-key-alias>"
$env:ANDROID_KEY_PASSWORD      = "<your-key-password>"

# 3. 执行私有签名 + 独立 MFi 认证打包
.\gradlew.bat :mobile:assemblePrivateRelease -PrequirePrivateSigning=true --no-daemon
# 产物位置：mobile\build\outputs\apk\release\mobile-release.apk
```

> [!NOTE]
> 从旧版公开 `debug` 签名切换至私有签名安装时，首次需先用「手机扫码备份设置」导出配置，卸载旧包后再安装私有签名包并扫码恢复；后续升级使用同一份私钥即可直接覆盖安装。

---

## 四、已知限制与日常建议

1. **原车高德联动**：仪表飞屏借用高德通道；投屏运行期间若语音让“小安”关闭高德，可能导致 CarPlay 短暂卡顿重连。
2. **跟随车灯与 Dock 主题**：当 iOS 判定本地时间为夜间时，CarPlay 会强制保持深色；Dock 图标需在 iPhone `CarPlay 设置 → 自定义图标` 中开启自动深浅色。
3. **日常安全建议**：
   - UNI-V 原厂仪表投屏无需车机网络 ADB，若未使用实验性「暂停后台 Wi-Fi 扫描」，建议关闭车机 5555 端口；
   - 日常无线优先使用 **车机热点** 或 **Wi-Fi Direct**；若使用「现有 Wi-Fi」连接公共路由器，建议在设置中开启 **「锁定已配对 iPhone」**；
   - 含离线 MFi 认证的自用安装包请仅用于本人实车，不要公开发布。

---

## 五、致谢与开源协议

- 原始上游项目：[shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay)（GPL-3.0）
- 感谢长安适配分支与车友实测反馈，以及 [zuo-qirun/amap-companion](https://github.com/zuo-qirun/amap-companion) 提供的仪表副屏思路
- 底层协议与音频组件基于 xcertplay（GPL-3.0）、SpeexDSP（BSD-3-Clause）、AndroidX、Bouncy Castle、JmDNS 等开源项目（详阅 [docs/THIRD_PARTY_NOTICES.md](docs/THIRD_PARTY_NOTICES.md)）
- 本项目遵循 **GPL-3.0** 开源协议，详见 [LICENSE](LICENSE) 与安全说明 [SECURITY.md](SECURITY.md)。
