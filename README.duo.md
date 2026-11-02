# DiPlayDuo

DiPlayDuo 在当前 DiPlay Legacy Android 分支上移植 `91d07be` 的独立应用身份、高德预热、CarPlay 主画面第二屏镜像及恢复画面等待提示，并参考 `DiPlay-Legacy-Android-0.2.21` 增加启动时自动开启原车 Wi-Fi 热点。保留当前分支的版本、最低系统要求、Release 签名校验和本地认证构建机制，不回退到旧版本。

本文描述源码设计和构建方式。原有移植已通过完整 CI 和普通 Debug 打包验证；新增热点功能的验证单独记录在文末，不沿用历史产物作为验证依据。**尚未进行 Release/独立认证打包或实车验证**。

## 应用身份与共存

| 项目 | 值 |
| --- | --- |
| 名称 | `DiPlayDuo` |
| applicationId | `com.shihab.diplay.duo` |
| versionName / versionCode | `0.3.14` / `134` |
| minSdk | 17（Android 4.2） |
| compileSdk / targetSdk | 37 / 37 |
| 原生 ABI | `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64` |

- Duo 与原版 `com.shihab.diplay` 使用不同包 ID，可共存；数据、授权、认证配置和配对不会自动迁移。
- 当前分支原先的 Debug `.hudtest` 包名后缀和 `-hud-test` 版本后缀在 Duo 中移除。Debug 与 Release 使用同一 Duo 包 ID 和名称，不能作为两个独立应用安装。
- Debug 和 Release 通常签名不同，不能互相覆盖更新。切换签名需要卸载已有 Duo（会丢失数据）或使用相同签名；不要因此绕过现有 Release 签名身份校验。
- 不要让两款应用同时争抢同一 USB、CarPlay 会话或车机显示资源。
- 地图嵌入 IPC 为 `com.shihab.diplay.duo.action.EMBED_MAP`；断开连接 action 为 `com.shihab.diplay.duo.DISCONNECT`。`home`、`maphost` 示例使用 Duo 的地图 action，第三方启动器也需同步适配。

## 实际启动流程

此流程在进入 `CarPlayHostActivity` 时执行，不意味着仅打开设置首页就会触发，也不意味着安装后无需配置即可连接。

1. **悬浮窗授权检查**：API 23 及以上检查 `Settings.canDrawOverlays()`，未授权时尝试打开 Duo 的系统授权页，仍需用户允许。从授权页返回后重新检查；同一进程避免反复自动请求。无法打开授权页不会阻断后续流程。API 29 及以上仍无授权时，完成热点阶段后跳过高德预热，降低无法从高德返回的概率。
2. **自动准备原车 Wi-Fi 热点（新增）**：有线连接，或无线选择“原车热点 / 手动热点”（`MANUAL`）时，尝试开启系统保存的热点，保留 SSID 和密码。缺少“修改系统设置”权限时打开 Duo 的系统授权页；这与悬浮窗授权不同，同一进程最多自动请求一次，返回后检查实际权限，不以结果码认定授权。单次开启尝试使用 **12 秒预算**；失败、拒绝授权或超时会提示并继续高德/CarPlay，不阻断有线启动。无线 `EXISTING_WIFI`、`WIFI_P2P` 或已启用的 `HOSTAPD` 模式跳过此阶段，不关闭它们使用的 Wi-Fi。
3. **高德预热**：先检查系统可见进程中的车机版 `com.autonavi.amapauto` 和手机版 `com.autonavi.minimap`。任一版本已运行则不重复拉起；否则优先尝试车机版，再尝试手机版。查询受固件/Android 可见性限制，未查到不等于一定未运行。每个 Duo 进程只尝试一次，不新增 root、ADB 或使用情况访问要求。
4. **返回 CarPlay**：成功启动高德后等待约 **2 秒**，尝试将原 CarPlay 任务移回前台并显式返回 `CarPlayHostActivity`。以 `onResume()` 确认恢复前台；每次请求后等待 1 秒检查，未恢复则再等 2 秒补试，首次加最多 3 次补试，共最多 **4 次**。恢复前台、Activity 退出/销毁或启动器关闭后停止，不在用户随后离开时重放。未安装/无法启动高德时继续 CarPlay。2 秒延时不证明高德导航或物理仪表路由已就绪。
5. **连接与等待画面**：随后执行原有 CarPlay 连接权限和会话流程。恢复已有后台会话时跳过上述授权、热点及高德预热，不干扰现有连接；当前主输出尚无新帧则保留连接阶段和等待秒数，不以历史首帧或仅仪表流活动判定本次主窗口已恢复；不为显示等待提示而断开后台音频。
6. **第二屏镜像**：主 CarPlay 视频实际提交首帧后，再创建第二屏输出。无保存目标时，单个可用副屏自动选择，多个副屏在前台选择；保存 ID 和名称，ID 变化时按唯一同名屏幕恢复。已保存目标暂时不可用时等待恢复，不随意改投其他屏幕。

启动顺序为 **悬浮窗授权 → 热点权限/开启 → 高德检查及预热 → 返回 Duo → CarPlay 连接 → 主画面首帧 → 第二屏镜像**。

### 自动热点的范围与限制

- 与参考版一致，进入 CarPlay 的有线/无线 `MANUAL` 启动阶段会自动尝试，**不受设置首页原有“自动开启原车热点”开关限制**；该开关仍控制原有首页/开机自动化。仅打开设置首页不会触发本次新增阶段。
- 开启热点可能按现有固件兼容逻辑先关闭普通 Wi-Fi；权限缺失、已取消或热点已开时不执行该前置处理。不会修改保存的热点名称/密码，也不会在退出 CarPlay 时主动关闭热点。
- 控制能力取决于系统权限和厂商固件。旧系统使用 `setWifiApEnabled(null, true)`；较新系统保留当前反射控制和已授权 ADB 回退。不会为此自动发起新的 ADB 配对或授权；不支持时仍需手动开热点。
- 12 秒是本阶段单次开启的预算，包含等候公共启动锁和 Wi-Fi 前置处理，不包含用户停留授权页的时间，也不能硬中断所有厂商 Binder 调用。原有开机、首页和无线连接阶段仍有自己的重试/超时策略，整个连接过程不保证 12 秒内结束。
- 关闭启动器会取消等待和后续回调，不关闭已成功开启的热点。首次权限授权、Activity 重建和特定固件行为仍需设备验证。

## 镜像及 HUD 的边界

- 镜像链路是 CarPlay 主视频流（110）→ 额外镜像解码器 → 第二屏 `Presentation` 的 `SurfaceView`；不使用 `MediaProjection`，不请求录屏授权。
- 只镜像 CarPlay 主视频，不镜像 Android 桌面、高德、设置页或通知。它与独立仪表地图流（111）不同；镜像启用时处理既有仪表投影窗口冲突。
- 使用默认 `Presentation` 窗口类型，不尝试 `TYPE_PHONE`/2002 或 `TYPE_SYSTEM_ALERT`/2003；悬浮窗授权不改变第二屏窗口类型。
- 按原比例居中并填充黑边。可用 Display、额外解码能力、厂商显示路由和后台启动限制均可能影响结果；应用不能绕过私有 Display 权限。
- 高德只通过普通启动 Intent 打开，不自动开始导航，也不调用厂商私有路由使能接口。窗口创建或向 Surface 提交帧，不等于物理仪表已显示。
- HUD/Starter Bridge 名单增加 Duo，保留既有包名与固件检查。名单变更不表示新车型已验证；调试 HUD 入口继续受调试构建、权限、UID 和指定固件限制，不因 Duo 身份开放给任意应用。
- minSdk 17 是构建兼容声明，不表示 API 17 支持所有上述功能；API 相关功能仍受系统能力和运行时检查限制。

## 首次使用

1. 安装 APK，确认名称为 **DiPlayDuo**；在 Duo 中重新配置连接方式、有效认证来源和 iPhone 配对。
2. 使用原车/手动热点时，在系统设置确认已保存的 SSID 和密码，并在 Duo 中填写匹配的信息；其他无线模式仍按原有方式配置网络。
3. 进入 CarPlay；若打开系统悬浮窗或“修改系统设置”授权页，为 Duo 授权后返回，等待自动热点准备、高德预热与 CarPlay 启动。不支持自动热点时先手动开启。
4. 多副屏时选择正确目标，在车上确认主屏、仪表、比例、断线重连及后台恢复。必要时手动返回 Duo；固件可能阻止自动返回。

## 打包

在本仓库根目录执行：

```bash
bash ./build-diplayduo.sh --help
bash ./build-diplayduo.sh debug
# 或 release / standalone-debug / standalone-release
```

| 模式 | Gradle 任务 | 要求 |
| --- | --- | --- |
| `debug`（默认） | `:mobile:assembleDebug` | 本地 Android Debug 签名 |
| `release` | `:mobile:assembleRelease` | 当前项目认可的 Release 密钥及显式签名变量 |
| `standalone-debug` | `:mobile:assembleStandaloneDebug` | 有效本地认证材料 |
| `standalone-release` | `:mobile:assembleStandaloneRelease` | 有效本地认证材料及认可的 Release 签名 |

脚本基于 `91d07be` 移植，适配当前目录、SDK 探测与已有签名/认证约束：

- 使用项目 Gradle Wrapper 9.5.0，Gradle Daemon 固定 JDK 25。可设置 `JAVA_HOME`；未设置时尝试 Gradle 已下载 JDK 和常见 Homebrew JDK 目录。
- SDK 优先 `ANDROID_HOME` / `ANDROID_SDK_ROOT`（同时设置时须相同），其次根目录 `local.properties` 的 `sdk.dir`，最后 macOS `~/Library/Android/sdk` 或其他系统 `~/Android/Sdk`。
- 需要 API 37 平台、Build Tools `37.0.0`、NDK `25.2.9519653`。SDK 许可须自行审阅接受；缺少依赖时脚本不自动接受许可。
- 构建后检查 APK 包 ID、应用名称和签名，读取实际版本，导出至 `dist/DiPlayDuo-<version>-<mode>-<timestamp>-<pid>.apk` 并生成同名 `.sha256` 文件。检查失败不导出。
- 脚本不执行单元测试、lint、仪器测试或实车验证。

### Release 签名

保留 `mobile/build.gradle.kts` 的严格签名身份校验，不允许 Release 回退到 Debug key，也不支持任意自建密钥替代项目认可的证书。配置：

```bash
export ANDROID_KEYSTORE_PATH=/absolute/path/to/approved-release.jks
export ANDROID_KEYSTORE_PASSWORD='...'
export ANDROID_KEY_ALIAS='...'
export ANDROID_KEY_PASSWORD='...'
bash ./build-diplayduo.sh release
```

未显式设置路径时，Gradle 保留根目录 `release-signing.jks` / `release-signing.keystore` 探测；密码、alias 仍需显式设置。不要将密钥、密码或认证材料提交到仓库。正式发布还需遵守 `AGENTS.md` 的版本控制信息、Release APK 源码校验和校验文件要求；脚本不能替代发布验证。

### 本地认证

优先显式指定本机私有目录：

```bash
export DIPLAY_AUTH_ASSETS_DIR=/absolute/path/to/private/runtime-assets
# 目录中包含 offline-mfi/identity.pk8 与 offline-mfi/certificate.p7b
bash ./build-diplayduo.sh standalone-debug
```

保留当前 Gradle 的 `.private/runtime-assets`、`auth-assets` 本地目录回退和凭证文件白名单。`standalone-*` 额外要求非空材料并执行已有密钥/证书匹配及挑战签名验证；这不是实车认证成功证明。普通 `debug`/`release` 不强制提供材料，但若本地目录已配置则仍遵循原有打包规则；没有有效材料的普通源码包不保证可独立完成 CarPlay 认证。

## 诊断与代码入口

```bash
adb logcat -v time -s DiPlay-Startup DiPlay-Mirror xcertplay-usb
```

- `car hotspot auto-enable: …`：热点开启结果；`Car hotspot is on` 表示已观测到热点开启，其余结果需检查权限或固件。
- `Leaving hotspot management to the selected wireless mode: …`：当前无线模式不使用本次新增的原车热点准备。
- `Gaode already running`：检测到高德进程，跳过拉起和预热等待。
- `Returning to CarPlay after Gaode warmup; attempt=…/4`：已请求返回；不代表系统已经允许。
- `CarPlay task resumed after Gaode warmup`：生命周期确认恢复前台。
- `CarPlay task did not resume after 4 attempts`：本轮已停止，检查悬浮窗/后台弹出权限或手动打开 Duo。
- 副屏窗口已创建但仪表不可见时，仍需检查 Display 可访问性、原车路由和解码能力，不能仅凭窗口日志判定成功。

主要入口：`CarPlayStartup.kt` 负责悬浮窗/热点权限、热点准备与高德预热，`CarHotspotTethering.kt` 负责保存热点的开启、取消、超时及旧系统兼容，`CarPlaySecondScreen.kt` 负责副屏选择和布局，`CarPlayHostActivity.kt` 负责启动/恢复接入，`AndroidMediaSink.kt` 与 `MainSurfaceFrameWaiter.kt` 负责当前主输出新帧证据。分享日志前请移除设备标识、网络信息等敏感内容。

## 验证状态与待验项

本说明不沿用来源分支的历史测试通过记录、旧 APK 路径、历史 SHA-256 或旧版本兼容结论。

2026-11-02 本次移植的实际验证结果（JDK 25 / 本机 Android SDK）：

- 完整检查命令成功：`shared` 1373 项测试（1 项跳过）、`common` 1017 项、`home` 4 项，合计 2394 项，0 失败、1 跳过。
- `mobile`、`home`、`maphost` 的 `lintDebug` 与 `assembleDebug` 全部成功。
- 已实际运行 `bash ./build-diplayduo.sh debug`，导出 `dist/DiPlayDuo-0.3.14-debug-20261102-175627-41414.apk` 和同名 `.sha256`。
- APK 元数据确认：`com.shihab.diplay.duo`、`DiPlayDuo`、版本 `0.3.14` / `134`、minSdk 17、targetSdk 37；`apksigner verify` 通过，v1/v2 签名有效。
- 初轮检查的日志环境、模拟 HUD 服务绑定和 macOS 规范路径断言问题已修正；另修正新解码器测试在 Surface 切换完成前提交帧的竞态，最终完整检查通过。未为通过测试改动热点或 HUD 生产实现。
- Release、standalone 构建、设备安装和实车验证均**未执行**。Debug 打包成功不等于 CarPlay 认证或物理仪表显示已验证。

仓库要求的完整检查：

```bash
bash ./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug
```

仍需验证冷启动/开机自启、悬浮窗允许与拒绝、高德已运行与未安装、有限返回重试、主屏后台恢复、静止画面、第二屏增删与重连，以及特定车型的物理仪表显示和解码性能。Robolectric 与 APK 签名检查不能替代这些验证。

### 2026-11-04 自动热点移植

- 在原有悬浮窗检查与高德预热之间增加热点阶段；新增独立“修改系统设置”结果回调、后台开启任务、12 秒尝试预算及关闭时取消。保留当前分支的高德有限返回重试、真实 SDK 判断及后台会话恢复逻辑。
- 热点公共开启流程的 Wi-Fi 前置处理纳入原有公共锁、取消与超时控制，且在确认热点未开、有权限之后执行；保留原有 Wi-Fi 冲突处理和 ADB 回退，不通过扩大权限绕过系统限制。
- 新增 `CarPlayStartupHotspotTest`，10 项测试覆盖授权与启动顺序、有线与无线模式、拒绝/缺失授权页、失败/超时继续、重复调用、启动器重建及关闭取消；保留既有高德和后台恢复回归。热点公共入口新增 15 个执行用例，覆盖状态/权限/取消、公共锁与预算、旧系统直接开启及两次关闭 Wi-Fi 的兼容逻辑。Android 4.2 分派测试是在 API 23 Robolectric 中模拟系统版本识别，不等于真实 API 17 设备验证。
- 实车仍需验证：有线与无线 `MANUAL` 自动开热点、已有热点不重开、SSID/密码不变、其他无线模式不受影响、首次授权/拒绝、开机并发与重连，以及不支持固件的手动回退。未进行设备安装或实车验证；2026-11-02 的历史 APK 不包含本次改动。
