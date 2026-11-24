# DiPlay 上游优化接入执行计划（面向长安 UNI-V 22 款领速版）

> **目标项目**：`E:\AndroidProject\diplay_changan-master\diplay_changan-master`（基于上游 `v0.2.11` 分叉，当前 `0.2.30 / versionCode 45`）  
> **上游来源**：`https://github.com/shihabal3amri/DiPlay`（`v0.2.12 → v0.2.22 → main`）  
> **目标车型**：长安 UNI-V 2022 款 2.0T 领速版（飞鱼 OS / Android 9 / 联发科车机芯片）  
> **实施原则**：**分阶段精准移植、严守长安红线、每阶段独立 Git 提交 + 编译与单元测试通过后再进入下一阶段**。

---

## 0. 全局约束与红线清单

### 0.1 必须保留的长安 UNI-V 专属能力（不可破坏）
任何阶段的改动都不得破坏以下长安定制模块的行为：
1. **免高德三指飞屏仪表投屏**：`common/.../InCallClusterBridge.kt`、`ClusterMapPresentation.kt`、三指左右滑与冷启动补投逻辑。
2. **自定义仪表/取景窗主题引擎**：`CustomThemeHost.kt`、`CustomThemeSpec.kt`、`CustomRuleEngine.kt`、`CustomStateMachine.kt`、`Porthole*View.kt`、`TwoFingerThemeSwipe.kt`、五卡片独立位置/缩放、画面裁剪 `ClusterCropPanel.kt`。
3. **车机硬件联动**：
   - 大灯与实时车况跨进程读取（`com.wt.n2048`）：`CarLampMonitor.kt`、`CarDataMonitor.kt`、`LampReaderService.kt`、`ILampReader.aidl`。
   - 原车氛围灯音乐律动：`IncallMusicRhythm.kt`（需保证音乐 `AudioTrack` 的 `audioSessionId` 正常传递）与 16 段音频频谱 `MusicReactiveBus.kt`。
   - 自动开关蓝牙 & 方向盘键：`AutoBluetooth.kt`、`BluetoothRestoreReceiver.kt`（延时关蓝牙、语音键短按屏蔽 Siri）。
4. **长安专属网络与音频调优**：
   - 2.4GHz 热点放行、热点地址 IPv4 优先、主动下发 Wi-Fi 配置 `0x5703`、Android 9 `Os.dup` ~12s TCP 半开检测。
   - 按连接方式分流的音频时钟模型：`shared/.../media/AudioTrimPolicy.kt`（Wi-Fi Direct 与热点分流）和 RFC 2198 音频冗余包：`AudioRedundancy.kt`。
5. **包名与覆盖升级兼容性**：保持 `mobile/build.gradle.kts` 中 `applicationId = "com.shihab.diplay.hudtest"`，不破坏已有配置和配对数据。

### 0.2 本机环境与认证资产说明
- **本机已就绪工具链**：
  - JDK 21：`E:\SDK\Gradle\jdks\jetbrains_s_r_o_-21-amd64-windows.2`
  - Android SDK：`D:\SDK\AndroidSDK`（已安装 `android-37.0`）
  - NDK：`D:\SDK\AndroidSDK\ndk\28.0.12433566`（注意：项目 `shared/build.gradle` 当前声明为 `28.2.13676358`，Phase 0 需对齐为本机版本 `28.0.12433566` 或安装对应小版本）
- **MFi 认证资产提示**：
  - 源码仓库不含 `offline-mfi/{certificate.p7b, identity.pk8}`。开发阶段可通过源码构建与单元测试验证全部功能；**最终上车测试前**需配置环境变量 `DIPLAY_AUTH_ASSETS_DIR` 指向包含上述两文件的目录，再执行 `:mobile:assembleStandaloneDebug` 或 `:mobile:assembleRelease`。

### 0.3 明确不接入的上游内容（降低维护负担）
- 比亚迪 DiLink 1.0–7.0 专属仪表/悬浮窗/电池/车标适配（`Byd*`、`DiLink*`、`Fce*`）。
- 吉利/领克/ECARX/Harman 专属适配（`Geely*`、`Ecarx*`、`Harman*`、`com.geely.*` AIDL）。
- 除简中/英文外的新增小语种（法/意/日/韩/土/越/繁中）。
- 上游应用内自动更新器（`update/*`，指向原作者 GitHub Release，不适用于长安定制版）。

---

## Phase 0：基线快照与本地编译打通

### 目标
建立可回滚的本地 Git 基线，打通 Gradle 编译与单元测试，确认改造前处于“零报错、测试全绿”的健康状态。

### 执行任务
1. **初始化本地 Git 仓库**：
   - 在项目根目录 `E:\AndroidProject\diplay_changan-master\diplay_changan-master` 执行 `git init`，将原始代码提交为 `baseline: diplay-changan 0.2.30 (before upstream integration)`。
   - 确认 `.gitignore` 忽略 `local.properties`、`build/`、`.gradle/`、认证资产等。
2. **配置本机 SDK / NDK**：
   - 创建 `local.properties`：`sdk.dir=D\:\\SDK\\AndroidSDK`。
   - 检查 `shared/build.gradle` 中 `ndkVersion`（当前 `28.2.13676358`），与本机 `28.0.12433566` 对齐以保证 `ndkBuild` 无需额外下载即可编译 `xcertplay_i2c` 和 `local_hotspot_radio`。
3. **验证基线构建与单测**：
   - 运行：
     ```powershell
     .\gradlew.bat :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:assembleDebug --no-daemon
     ```
   - 记录基线编译耗时、APK 产物路径与测试通过数量。

### 验收标准
- `git status` 干净，存在清晰的基线 commit。
- `:shared:testDebugUnitTest`、`:common:testDebugUnitTest` 全绿，`:mobile:assembleDebug` 成功生成 APK。

---

## Phase 0.5：代码评审轻量体检修复（长安底座加固）

### 目标
在接入上游优化前，先以最小改动消除评审中发现的 3 个高性价比风险点（零业务回归、改动完全隔离）。

### 具体修复清单
1. **Release 关闭调试广播 `BenchClusterReceiver` 暴露**：
   - 新增 `mobile/src/release/AndroidManifest.xml`，通过 `tools:node="remove"` 在 Release 包中移除 `BenchClusterReceiver`；Debug 包保留完整 `adb` 调试能力。
2. **`CarLampSource` 反射缓存 & `LampReaderService` 消除空转**：
   - 在 `common/.../daynight/CarLampSource.java` 成功获取 `propMgr` 时一次性缓存 `getIntProperty` / `getFloatProperty` / `getIntArrayProperty` / `isPropertyAvailable` 反射 `Method` 对象，并补充 `disconnect()` 方法；
   - 在 `common/.../daynight/LampReaderService.kt` 中连接成功后停止 1s `retry` 空转循环，并在 `onDestroy()` 释放连接。
3. **`CustomThemeLayerView` 渲染与内存优化**：
   - 在 `common/.../CustomThemeLayerView.kt` 中缓存不可变的 `brush` `Shader` 对象，消除 30fps 每帧 `new Gradient` + `toIntArray()` 分配；
   - 移除 `drawMovie()` 中冗余的 `postInvalidateOnAnimation()`（已由 `CustomThemeHost` 33ms 驱动）；
   - 对图片解码增加 `inSampleSize` 画布尺寸上限（1920×720）并在 `setRuleImage` 做按文件名缓存，避免 OOM 与重复读盘。

### 验收标准
- `:common:testDebugUnitTest`、`:mobile:assembleDebug`、`:mobile:assembleRelease` 全部通过；核验 Release 合并后的 Manifest 不再包含 `BenchClusterReceiver`。
- 提交独立 commit：`fix(changan-audit): tighten release BenchClusterReceiver, cache CarLampSource reflection and optimize CustomThemeLayerView`。

---

## Phase 1：视频解码与触控流畅度优化（对应第 2 部分）

### 目标
直接提升 22 款 UNI-V（联发科车机 + Android 9）的画面解码延迟、异常恢复能力与触控跟手度，同时严守长安三指手势与主屏合成优化。

### 具体接入清单

| 编号 | 优化项 | 上游参考文件 / PR | 本地改动点 | 风险与兼容处理 |
| :--- | :--- | :--- | :--- | :--- |
| 1.1 | **触控通道 `TCP_NODELAY` & 慢连接拖拽合并** | `AirPlayTouchSlots.kt`、PR #311 / #1032 | 新增 `shared/.../airplay/AirPlayTouchSlots.kt`；核实 `AirPlaySession.kt` 控制通道与事件/触控通道（`eventSocket`）均开启 `tcpNoDelay = true`（L858 已有部分设置）；修改 `CarPlayHostActivity.kt` 合并高频 Move 事件 | 保持三指飞屏与二指切主题拦截在最上层，仅合并单指/多指送往 CarPlay 的 Move 帧 |
| 1.2 | **MediaTek（联发科）低延迟解码调优** | `shared/.../media/MtkDecoderTuning.kt`、PR #608 / #1353 | 新增 `MtkDecoderTuning.kt`；在 `AndroidMediaSink.kt` / `MediaCodecStartup.kt` 解码器配置阶段接入（配置开关默认关闭，支持在设置中开启并带安全回退） | Android 9 MediaTek 硬解若不认低延迟 key 需捕获异常并自动回退标准配置，避免黑屏 |
| 1.3 | **视频队列减分配 & 积压恢复** | `VideoDecodeQueue.kt`（PR #1318）、`VideoBacklogRecovery.kt`（PR #496） | 更新 `shared/.../media/VideoDecodeQueue.kt` 复用缓冲区减少 GC；新增 `VideoBacklogRecovery.kt` 接入主屏解码循环，网络抖动积压时快速跳至关键帧 | 与长安“画面优先级最高、不推迟画面”原则一致，不引入强制延迟缓冲 |
| 1.4 | **HEVC 无硬解回退 H.264 & 倒车后画面恢复** | `common/.../HevcSupport.kt`、`shared/.../media/VideoSurfaceHandoff.kt`、PR #669 / #707 | 新增 `HevcSupport.kt` 与 `VideoSurfaceHandoff.kt`；在开启高效视频（HEVC）时检测硬解能力自动兜底；在 `CarPlayHostActivity.kt` 恢复 Surface 与倒车影像退出后的画幅比例 | 不改动 `ClusterMapPresentation.kt` 仪表渲染逻辑，仅改善主屏 Surface 切换与尺寸重算 |
| 1.5 | **设置项持久化与 UI 入口** | `AirPlayPersistence.kt`、`CarPlayRuntimeConfig.kt`、`DiPlayActivity.kt` | 新增“低延迟解码（联发科/低延迟）”开关及文案（`values/strings.xml` + `values-zh-rCN/strings.xml`） | 默认关闭，供实车按需开启对比体验 |

### 验收标准
- 新增/更新单测通过：`VideoDecodeQueueTest`、`MtkDecoderTuningTest`、`VideoBacklogRecoveryTest`、`HevcSupportTest`、`AirPlayTouchSlotsTest`。
- 现有长安单测（含 `TwoFingerThemeSwipeTest`、`PortholeThemeTest` 等）全部通过。
- `:mobile:assembleDebug` 编译通过，提交独立 commit：`feat(video-touch): port upstream MTK low-latency decode, backlog recovery, HEVC fallback and TCP_NODELAY touch`。

---

## Phase 2：手机扫码备份/恢复设置、扫码拉日志与设置搜索（对应第 4 部分）

### 目标
解决长安车机无文件选择器、重装易丢精细配置（5 张仪表卡坐标/缩放、主题目录、音频声道/缓冲）、导出日志麻烦等高频痛点。

### 具体接入清单

| 编号 | 优化项 | 上游参考文件 / PR | 本地改动点 | 风险与兼容处理 |
| :--- | :--- | :--- | :--- | :--- |
| 2.1 | **手机扫码备份与恢复设置** | `common/.../backup/SettingsBackupCodec.kt`、`SettingsBackupServer.kt`、`SettingsQrCode.kt`、`assets/settings-backup.html`、`settings-code.html`（PR #598） | 移植整个 `backup/` 包与 2 个静态 HTML 页面；在 `SettingsBackupCodec.kt` 中**额外注册长安版独有配置键**（仪表五卡坐标/缩放、裁剪 rect、取景窗主题与目录、自动开关蓝牙/延时/短按屏蔽 Siri、大灯联动、氛围灯、独立缓冲/声道） | 仅在用户主动打开备份弹窗时启动局域网临时 HTTP 服务，关闭弹窗立即停止端口；不导出 MFi 认证或敏感配对私钥 |
| 2.2 | **手机扫码下载诊断报告** | 上游诊断报告扫码下载逻辑（PR #686 / #687） | 复用本地 HTTP + QR 码服务，在长安版现有诊断弹窗中增加“手机扫码下载”入口（与现有“保存到目录 / 屏幕查看 / 分享”共存） | 确认支持热点/Wi-Fi 局域网 IPv4 地址展示，便于手机直接扫码访问 |
| 2.3 | **设置页内联关键词搜索** | PR #450 | 在 `DiPlayActivity.kt` 设置页顶部增加轻量搜索过滤框，支持按中文/英文关键词快速过滤设置项 | 仅做过滤展示，不重构长安现有设置分组结构 |

### 验收标准
- 新增/更新单测通过：`SettingsBackupCodecTest`（重点覆盖长安版特有配置键的序列化/反序列化往返一致性）、`SettingsQrCodeTest`。
- `:common:testDebugUnitTest`、`:mobile:assembleDebug` 通过，提交独立 commit：`feat(tools): port QR settings backup/restore (with Changan prefs), QR log download and settings search`。

---

## Phase 3：音频与通话质量升级（对应第 1 部分）

### 目标
引入上游原生 SpeexDSP 回声消除与语音滤波、音频线程高优先级、音乐丢包填充、麦克风稳定性修复及可选「纯显示 + 原车蓝牙音频」模式，同时完整保留长安版 `AudioTrimPolicy`、`AudioRedundancy`、氛围灯律动和频谱总线。

### 具体接入清单

| 编号 | 优化项 | 上游参考文件 / PR | 本地改动点 | 风险与兼容处理 |
| :--- | :--- | :--- | :--- | :--- |
| 3.1 | **SpeexDSP JNI 回声消除 & 语音滤波** | `shared/src/main/jni/speex_echo_jni.c`、`jni/speexdsp/*`、`Android.mk`、`SpeexEchoCanceller.kt`、`EchoReference.kt`、`VoiceFilter.kt`、`CallSpeaker.kt`（PR #370 / #421） | 引入 SpeexDSP C 源码与 JNI 包装，更新 `shared/src/main/jni/Android.mk` 编译 `libspeex_echo.so`；移植 Kotlin 封装类并接入 `MicrophoneUplink.kt` 与 `AndroidMediaSink.kt`（通话扬声器回采参考信号） | 默认作为设置页可选开关（通话回声消除/语音滤波），防止部分车机 ROM 自身已带硬件 AEC 时产生双重处理失真；确认 `arm64-v8a` & `armeabi-v7a` 均能正常编译打包 |
| 3.2 | **音乐丢包填充 & 音频线程高优先级** | `shared/.../media/LostMusicFill.kt`（PR #1206）、PR #788 / #804 | 新增 `LostMusicFill.kt` 接入 `AndroidMediaSink.kt`；为音频解码/输出线程设置 `Process.THREAD_PRIORITY_AUDIO`；合入通话/打断结束后音乐恢复逻辑 | **严禁覆盖** `AudioTrimPolicy.kt`（热点 vs Wi-Fi Direct 分流）和 `AudioRedundancy.kt`（RFC 2198），且保留 `IncallMusicRhythm` 的 `audioSessionId` 回调与 `MusicReactiveBus` PCM 喂入 |
| 3.3 | **麦克风与语音录音修复** | `MicrophoneCaptureCheck.kt`、PR #295 / #547 / #548 / #644 / #1148 / #1283 | 更新 `MicrophoneUplink.kt` / `MicrophonePacketizer.kt`：支持 48kHz USB 麦克风安全分包、修正 24kHz Siri / 16kHz Opus 时钟、首帧录音失败自动重启麦克风 | 与现有软件 Opus 编码器（`OpusEncoder.kt`）兼容，不破坏 Android 9 无硬编 Opus 设备的通话/Siri |
| 3.4 | **可选「纯显示 + 原车蓝牙音频」模式** | `CarPlayMediaKeys.kt`（PR #1291）、`CarPlayDisplayOnlySessionTest.kt` | 在 `CarPlayMediaKeys.kt` 与设置项中新增可选开关：允许画面走 CarPlay、音频继续走车机原生蓝牙 | **与长安 `AutoBluetooth.kt` 互斥联动**：开启“走原车蓝牙音频”时，自动跳过关闭车机蓝牙并在 UI 给出提示，避免逻辑打架 |

### 验收标准
- NDK 编译产出 `libspeex_echo.so`（检查 APK `lib/arm64-v8a` 和 `lib/armeabi-v7a` 中包含 `libspeex_echo.so`、`libxcertplay_i2c.so`、`liblocal_hotspot_radio.so`）。
- 新增/更新单测通过：`LostMusicFillTest`、`RtpSequenceGapTest`、`CarPlayDisplayOnlySessionTest`、`AudioRedundancyTest`、`MusicReactiveBusTest`、`MicrophoneCaptureStatsTest`、`TelephonyMicrophoneTest`。
- `:shared:testDebugUnitTest`、`:common:testDebugUnitTest`、`:mobile:assembleDebug` 通过，提交独立 commit：`feat(audio): port SpeexDSP AEC/voice filter, LostMusicFill, mic fixes and display-only Bluetooth mode`。

---

## Phase 4：连接稳定性、故障诊断与安全加固（对应第 3 部分）

### 目标
提升无线启动速度与抗干扰能力、强化有线 USB 容错、在首页/等待页给出明确故障原因与崩溃堆栈、并合入 `v0.2.22` 的协议层输入安全加固，同时严格保留长安热点与 Android 9 半开检测修复。

### 具体接入清单

| 编号 | 优化项 | 上游参考文件 / PR | 本地改动点 | 风险与兼容处理 |
| :--- | :--- | :--- | :--- | :--- |
| 4.1 | **无线启动提速 & 热点地址超时刷新** | `FirstTcpWatchdog.kt`、`WirelessAirPlayHandshake.kt`、`HotspotAddressRecovery.kt`（PR #229 / #474 / #1057） | 接入慢解析提速、首次无线超时刷新热点地址、蓝牙打开瞬间自动重试（`CarPlayController.kt`、`WirelessHotspotManager.kt`） | **严禁破坏**长安 2.4GHz 放行、IPv4 优先、主动下发 `0x5703` Wi-Fi 配置和 Android 9 `Os.dup` ~12s 半开检测（`TcpLiveness.kt`） |
| 4.2 | **Wi-Fi 后台扫描暂停（可选）** | `WifiScanPause.kt`、`WifiScanPauseSession.kt`（PR #570 / #1346） | 移植 `WifiScanPause*` 到 `shared/.../network/` 并接入 `CarPlayController.kt`；在设置中提供开关（默认关闭） | 仅在车机已授权网络 ADB 时生效；无 ADB 时静默跳过并记录日志，不阻塞连接、退出会话时确保恢复扫描 |
| 4.3 | **明确故障原因提示 & 崩溃记录** | `ConnectionFailureCause.kt`、`ConnectionFailureGuidance.kt`、`LastConnectionProblem.kt`、`CrashRecord.kt`（PR #700 / #827 / #1058 等） | 移植故障分类与中文提示到首页和 CarPlay 等待页（如未配对 iPhone、热点未开启、VPN 被拒、iPhone 无画面）；接入未捕获异常记录并写入诊断报告 | 仅保留清晰中文/英文提示，复用长安现有 `DiPlayApp.kt` 入口注册崩溃记录器 |
| 4.4 | **有线 USB 稳定性增强** | `WiredStartAgain.kt`、`WiredSessionMemory.kt`、`NcmTrafficCounter.kt`、`UsbNeighborDiscovery.kt`（PR #575 / #625 / #907 / #962 / #1212） | 强化 `NcmUsbBridge.kt`、`IphoneUsbHost.kt`、`CarPlayController.kt`：损坏 NCM 包不断线、重插首次秒连、被阻断有线启动自动重试 | 不改变长安无线默认优先行为，提升用户偶尔插线使用时的体验 |
| 4.5 | **协议输入安全加固（v0.2.22）** | PR #1122–#1144、#1294 / #1296 / #1297 / #1298 / #1307 | 同步加固 `RtspMessage.kt`、`BplistCodec.kt`、`Tlv8Codec.kt`、`ControlFrameBuffer.kt`、`Iap2CsmFramer.kt`、`MediaClockTrim.kt`（限制报文上限、防整数溢出、防 NUL 截断与恶意帧） | 纯防御性边界检查，不影响正常 iPhone CarPlay 协议帧 |

### 验收标准
- 新增/更新单测通过：`WifiScanPauseTest`、`NcmTrafficCounterTest`、`UsbNeighborDiscoveryTest`、`WiredStartAgainTest`、`ConnectionFailureCause` / 安全边界相关测试。
- 全工程回归构建与测试通过：
  ```powershell
  .\gradlew.bat :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:assembleDebug :mobile:assembleRelease --no-daemon
  ```
- 提交独立 commit：`feat(connection-security): port wireless startup speedup, Wi-Fi scan pause, failure guidance, crash record, USB stability and protocol hardening`。

---

## 5. 最终整体验收与上车验证清单

### 5.1 本机自动验证（全部由 Gradle 完成）
- [x] `:shared:testDebugUnitTest` 全部通过（含原有 + 新移植单测）
- [x] `:common:testDebugUnitTest` 全部通过（含长安自定义主题、卡片、默认值迁移 + 新移植单测）
- [x] `:mobile:assembleDebug` & `:mobile:assembleRelease` 成功构建，APK 中正确包含 `libspeex_echo.so`
- [x] 如已提供 `DIPLAY_AUTH_ASSETS_DIR`：`:mobile:assembleStandaloneDebug` / `:mobile:assemblePrivateRelease` 通过认证资产校验并生成可直接上车的安装包

### 5.2 22 款 UNI-V 领速版实车核验项（上车测试）
- [ ] **长安核心回归**：无线 2.4GHz 热点正常建连、三指飞屏切换（原车/整机镜像/仅导航/取景窗）、自定义仪表主题车速/转速/频谱正常、大灯联动日夜模式正常、氛围灯律动正常、自动开关蓝牙正常。
- [ ] **Phase 1 验证**：触控拖动更跟手；开启「低延迟解码」后画面正常无花屏/黑屏；挂倒挡退出倒车影像后 CarPlay 画面比例正常。
- [ ] **Phase 2 验证**：手机扫码导出/恢复配置成功（含长安五卡片与主题设置）；手机扫码下载诊断报告成功；设置页搜索正常。
- [ ] **Phase 3 验证**：开启 SpeexDSP 通话回声消除后通话对方无明显回声；音乐播放无“忽快忽慢”；测试「纯显示 + 原车蓝牙音频」开关与自动关蓝牙无冲突。
- [ ] **Phase 4 验证**：断连/热点未开时首页提示清晰原因；有线插拔（若车机 USB 支持数据）可一次连上。

---

## 6. 回滚策略
每个 Phase 均对应独立 Git Commit（Phase 0 ~ Phase 4 共 5 个提交）。如实车验证发现某一阶段引发兼容问题，可直接 `git revert <commit>` 精确回退该阶段，不影响其余阶段优化。


---

## Phase 5：专项安全审计风险应对与纵深加固计划（2026-11-23 追加）

### 背景与目标
针对车机实际威胁模型（同车机第三方/破解 App、车机热点与同局域网设备、安装包逆向提取、行驶中前台干扰），对安全审计识别出的 **9 个风险点（3 高 / 4 中 / 2 低）** 制定可落地的工程化应对方案。  
**实施原则**：所有安全加固必须 **100% 向后兼容已有用户数据（支持旧明文配置平滑迁移）**，且绝不破坏 22 款长安 UNI-V（Android 9 / MTK）的仪表投屏、主题渲染、蓝牙联动与连接速度。

---

### 7.1 风险点与应对方案矩阵

#### 🔴 高风险项（优先实施：Phase 5A）

| 风险编号 | 风险描述与 CWE | 涉及模块 / 文件 | 工程化应对方案 | UNI-V 兼容与回退保障 |
| :--- | :--- | :--- | :--- | :--- |
| **SEC-01** | **Release 包默认复用公开 Debug 签名**<br>（CWE-295：同包名恶意 APK 静默覆盖劫持、继承 USB/无障碍/ADB 权限） | `mobile/build.gradle.kts`<br>`.gitignore`<br>仓库外密钥目录 | 1. 在仓库外安全目录（如 `D:\Download\diplay-auth-assets\keystore\`）生成车主专属私有 `4096-bit RSA` 签名密钥（开启 v1 + v2 签名兼容 Android 9）；<br>2. 在 `mobile/build.gradle.kts` 增加 `-PrequirePrivateSigning=true` 构建守卫与未配置 `ANDROID_KEYSTORE_PATH` 时的醒目编译告警，防止正式装车包误用 `debug` 签名；<br>3. 确认 `.gitignore` 严格忽略 `*.jks`、`*.keystore`、`keystore.properties`。 | 首次切换私有签名时：先用 Phase 2「手机扫码备份」导出五卡坐标与主题配置 → 卸载车机旧包 → 安装私有签名包 → 扫码一键恢复，配置零丢失。 |
| **SEC-02** | **本地高权限私钥与敏感凭据明文存储**<br>（CWE-312：`adbkey.private` 持 `shell` 权限，AirPlay/Lockdown 私钥与热点密码明文落盘） | 新增 `shared/.../security/KeystoreSecretBox.kt`<br>`shared/.../adb/AdbKeys.kt`<br>`common/.../AirPlayPersistence.kt` | 1. 基于 Android Keystore（`AES/GCM/NoPadding` 256-bit 硬件/TEE 主密钥，不设锁屏解锁约束以适配无锁屏车机）实现 `KeystoreSecretBox`，密文带 `enc:v1:` / `DPK1` 版本头；<br>2. 改造 `AdbKeys.kt`：加密存储 `adbkey.private`，首次读取旧版明文 PKCS#8 时自动原地迁移为密文；<br>3. 改造 `AirPlayPersistence.kt`：对 `identity_private`、`lockdown_host_private`、`lockdown_root_private`、`manual_hotspot_passphrase`、`remote_mfi_token` 实施透明加解密与平滑迁移。 | 若个别魔改 Android 9 车机 Keystore HAL 异常，捕获异常记录诊断标记并降级可用，严禁引发启动崩溃或丢失已有 iPhone 配对。 |
| **SEC-03** | **车机网络 ADB（`0.0.0.0:5555`）局域网暴露面**<br>（开启系统网络 ADB 时同网段可连接干扰或攻击 `adbd`） | `shared/.../adb/LocalAdb.kt`<br>`common/.../DiPlayActivity.kt`<br>`values*/strings.xml` | 1. 在 `LocalAdb.kt` 中强制校验目标地址 `InetAddress.isLoopbackAddress`，从代码层锁死仅允许连接回环地址（`127.0.0.1` / `::1`）；<br>2. 在设置页「连接期间暂停 Wi-Fi 扫描」说明中明确标注 UNI-V 原生仪表无需网络 ADB；<br>3. 当检测到 `127.0.0.1:5555` 处于开放状态但当前未启用任何需 ADB 的功能时，在设置/诊断报告给出关闭车机 5555 端口的安全提示。 | 不影响确实需要 `WifiScanPause` 用户的本地回环调用，同时引导 UNI-V 车主日常关闭 5555 暴露端口。 |

---

#### 🟡 中风险项（组件边界与局域网加固：Phase 5B）

| 风险编号 | 风险描述与 CWE | 涉及模块 / 文件 | 工程化应对方案 | UNI-V 兼容与回退保障 |
| :--- | :--- | :--- | :--- | :--- |
| **SEC-04** | **导出广播接收器监听非受保护自定义 Action**<br>（CWE-926：第三方 App 可伪造 `ACTION_BOOT_HU` / `ACTION_SHUTDOWN_HU` 在行驶中强拉前台或切蓝牙） | `common/src/main/AndroidManifest.xml`<br>`common/.../BootReceiver.kt`<br>`common/.../bluetooth/BluetoothRestoreReceiver.kt`<br>`mobile/src/debug/AndroidManifest.xml` | 1. `BootReceiver.kt`：对非系统保护的 `ACTION_BOOT_HU` 增加开机时间窗口限制（仅在 `SystemClock.elapsedRealtime() <= 180_000L` 即开机 3 分钟内生效）与单进程幂等防重，保留系统受保护 `ACTION_BOOT_COMPLETED` 原逻辑；<br>2. `BluetoothRestoreReceiver.kt`：对 `ACTION_BOOT_HU` / `QUICKBOOT_POWERON` 同样施加开机窗口限制；对 `ACTION_SHUTDOWN_HU` 增加无活跃投屏会话或关机状态校验；<br>3. 为 Debug 包的 `BenchClusterReceiver` 增加 `android:permission="android.permission.DUMP"`，阻止普通第三方 App 发送模拟数据。 | 车机真实冷启动/STR 快启均在开机窗口内触发，不影响开机自启与自动还原蓝牙，同时彻底封堵行驶中被恶意 App 伪造广播劫持。 |
| **SEC-05** | **`MapEmbedService` 导出无权限与解码实例耗尽风险**<br>（CWE-284：开启共享后任意 App 可窃取实时导航画面或耗尽 MTK 硬解实例） | `common/src/main/AndroidManifest.xml`<br>`common/src/main/res/values*/bools.xml`<br>`common/.../MapEmbedService.kt` | 1. **按系统版本裁剪暴露面**：利用 `values/bools.xml`（`false`）与 `values-v30/bools.xml`（`true`）控制 `android:enabled="@bool/enable_map_embed_service"`，在 **Android 9（22 款 UNI-V）上直接从包管理层禁用该导出服务**；<br>2. 在 `MapEmbedService.kt`（Android 11+）中校验绑定方宽高边界（`1..4096`）、限制最大并发嵌入实例数 `MAX_EMBEDS = 1`，并增加调用包名白名单/首次授权确认机制。 | 22 款 UNI-V（Android 9）本身不支持 API 30 `SurfaceControlViewHost`，默认禁用后导出攻击面直接归零，不影响长安 `InCallClusterBridge` 仪表投屏。 |
| **SEC-06** | **扫码备份/日志 HTTP 服务局域网暴力锁定与 CSRF**<br>（CWE-319 / CWE-307 / CWE-352：64-bit Token 偏短、5 次错输全局锁死、导入接口缺 CSRF 校验） | `common/.../backup/SettingsBackupServer.kt` | 1. 将随机路径 Token 从 `ByteArray(8)`（64-bit）升级为 `ByteArray(16)`（128-bit，32 位 hex）；<br>2. 将 6 位数字验证码错误计数改为**按客户端源 IP 独立限流**（单 IP 5 次锁定，全局总上限 20 次），防止局域网其他设备恶作剧锁死车主输入；<br>3. 仅放行 RFC1918 私网 / 链路本地源 IP 连接，对设置导入 `POST` 请求强校验有效路径 Token 与同源 `Origin`，并补充 `X-Content-Type-Options: nosniff`、`Referrer-Policy: no-referrer`。 | 手机扫码操作流程完全不变，抗局域网干扰与防跨站误导入能力显著增强。 |
| **SEC-07** | **外部自定义主题目录篡改与超大资源 DoS**<br>（CWE-732 / CWE-400：`/data/local/tmp/themes` 全局 `shell` 可写，超大 JSON/图片可致 OOM） | `common/.../CustomThemeSpec.kt`<br>`common/.../CustomThemeLayerView.kt`<br>`common/.../DiPlayActivity.kt` | 1. 在 `CustomThemeSpec.kt` 增加严格解析配额：`theme.json <= 512 KiB`、图层数 `<= 128`、动画数 `<= 64`、状态与规则数上限，并在解码前限制单张图片文件体积 `<= 16 MiB`；<br>2. 在主题设置中增加「导入到应用安全目录」动作：支持将外部 `/data/local/tmp/themes` 或自定义目录的主题一键复制进私有沙盒 `filesDir/themes/` 运行，脱离公共可写目录。 | 完全兼容现有全部自定义主题 `theme.json` 语法与二指滑动切主题，杜绝畸形主题文件拖垮主进程。 |

---

#### 🟢 低风险与合规控制项（连接锁定与产物管理：Phase 5C）

| 风险编号 | 风险描述与场景 | 涉及模块 / 文件 | 工程化应对方案 | UNI-V 兼容与回退保障 |
| :--- | :--- | :--- | :--- | :--- |
| **SEC-08** | **APK 内置离线 MFi 实验证书逆向提取风险**<br>（安装包内 `assets/offline-mfi/` 可被解压提取） | `mobile/build.gradle.kts`<br>`SECURITY.md` | 1. 保持证书严格存放在仓库外 `D:\Download\diplay-auth-assets\offline-mfi\`，由 `rejectBundledCredentials` 任务持续守护源码树零凭据；<br>2. 构建产物分级管理：未注入环境变量时构建纯净分享包；注入证书构建出的装车包归档标记为自用（不上传公开仓库/网盘）。 | 维持零硬件改机上车即用能力，同时确保 Git 仓库可安全开源/备份。 |
| **SEC-09** | **「现有 Wi-Fi」公共局域网模式下未认证配对抢连**<br>（公共路由器同网段设备可主动向 `:7000` 发起 `pair-setup`） | `shared/.../airplay/AirPlaySession.kt`<br>`common/.../AirPlayPersistence.kt`<br>`common/.../DiPlayActivity.kt` | 1. 新增可选开关 **「锁定已配对 iPhone（拒绝新设备配对）」**（默认关闭）：车主完成首次配对开启该开关后，`AirPlaySession` 直接拒绝未知设备的 `pair-setup` 请求，仅允许已在 `PairingStore` 中的公钥通过 `pair-verify` 建连；<br>2. 选择「现有 Wi-Fi」模式时在 UI 显示局域网安全提示，推荐日常优先使用 **Wi-Fi Direct** 或 **车机热点**。 | 默认关闭不影响首次配对体验；开启后即使误连公共 Wi-Fi 也不会被他人设备抢占投屏。 |

---

### 7.2 分批实施节奏与提交规划

为保持与前序阶段一致的“小步快跑、独立可回滚”原则，Phase 5 拆分为 **3 个独立 Git Commit** 执行：

1. **Phase 5A（签名防劫持与凭据硬件加密）**
   - 覆盖：`SEC-01`、`SEC-02`、`SEC-08`
   - 新增单测：`KeystoreSecretBoxTest`（加解密往返、篡改密文鉴权失败、旧明文配置自动迁移）、`AdbKeysMigrationTest`。
   - 计划 Commit：`sec(storage-signing): encrypt local private keys/passphrases with Android Keystore and add private release signing guard`

2. **Phase 5B（Android 组件导出面与本地服务加固）**
   - 覆盖：`SEC-03`、`SEC-04`、`SEC-05`、`SEC-06`、`SEC-07`
   - 新增/更新单测：`BootReceiverSecurityTest`（开机窗口拦截测试）、`SettingsBackupServerTest`（128-bit Token、按 IP 限流防全局锁死、POST Origin/CSRF 校验）、`CustomThemeBoundsTest`（超大 JSON/图层配额拦截）、`LocalAdbLoopbackTest`。
   - 计划 Commit：`sec(ipc-lan): harden boot/bt receivers, gate MapEmbedService to API 30+, upgrade backup server rate-limit/token and bound custom themes`

3. **Phase 5C（AirPlay 配对锁定开关与安全提示闭环）**
   - 覆盖：`SEC-09`（及 `SEC-03` 设置页提示文案）
   - 新增单测：`AirPlayPairLockTest`（开启配对锁定后拒绝 `pair-setup`、放行已配对设备 `pair-verify`）。
   - 计划 Commit：`sec(airplay): add optional paired-device lock mode and wireless security guidance`

---

### 7.3 Phase 5 验收标准清单

- [x] **自动化测试全绿**：新增安全单测与既有长安/音频/视频回归测试全部通过（`:shared:testDebugUnitTest`、`:common:testDebugUnitTest`）。
- [x] **Manifest 暴露面核验**：检查 `:mobile:assembleRelease` 合并后的 `AndroidManifest.xml`：
  - `BenchClusterReceiver`、`MyCarAppService` 已彻底移除；
  - `MapEmbedService` 在 API < 30（Android 9 车机）下处于 `enabled=false`；
  - `BootReceiver` 与 `BluetoothRestoreReceiver` 具备运行时开机窗口保护。
- [x] **平滑迁移与实车无感验证**：
  - 模拟旧版明文 `xcertplay_airplay.xml` 与 `adbkey.private` 升级后，已配对的 iPhone 无需重新配对即可直接无线自动重连，落盘文件不再包含明文私钥与热点密码；
  - 使用私有 `.jks` 签名产出最终装车 Release APK，通过 `apksigner verify --print-certs` 校验为自有证书（非 Android Debug 证书）。
