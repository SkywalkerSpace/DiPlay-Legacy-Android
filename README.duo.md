# DiPlayDuo

DiPlayDuo 是基于 DiPlay Legacy Android 0.2.21 的独立应用版本，增加高德启动预热、原车热点自动开启，以及 CarPlay 主画面第二屏镜像功能。

本文记录 Duo 的当前实现、安装和打包方式，不替代或修改原项目 README。

## 1. 应用身份与共存

| 项目 | 当前值 |
| --- | --- |
| 应用名称 | `DiPlayDuo` |
| Duo 包 ID | `com.shihab.diplay.duo` |
| 原版包 ID | `com.shihab.diplay` |
| versionName | `0.2.21` |
| versionCode | `41` |
| 最低 Android 版本 | Android 4.4 / API 19 |
| compileSdk / targetSdk | 37 / 37 |
| 原生 ABI | `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64` |

Duo 与原版使用不同包 ID，可同时安装，不会覆盖原版；应用数据、授权和配对配置也彼此独立，需要在 Duo 中重新配置。

当前 Debug 与 Release 使用相同的 Duo 包 ID，没有 `.hudtest` 后缀。二者不是两个独立应用；更新同一个 Duo 时必须保持签名一致。正式长期使用应固定自己的 Release 签名密钥。

共存不代表能同时占用同一套 USB、CarPlay 或车机显示资源。连接 iPhone 时建议退出另一款应用，避免两者争抢连接或 USB 默认处理程序。

## 2. 功能与启动顺序

以下自动流程在进入 CarPlay 主界面的启动过程中执行，不表示每次仅打开设置首页都会执行，也不表示安装后无需初始配置即可自动连接。

1. **检查悬浮窗权限**
   - Android 6.0 / API 23 及以上，在 CarPlay 启动流程中检查 `Settings.canDrawOverlays()`；未授权时自动打开本应用的系统悬浮窗授权页，仍需用户手动允许。
   - 从授权页返回后重新检查权限，再继续准备热点和高德预热；同一进程最多自动请求一次，避免拒绝后反复弹出。重新启动应用进程后可再次请求。
   - Android 10 / API 29 及以上若仍未授权，则跳过高德预热，直接继续 CarPlay，避免打开高德后被系统拦截后台返回。授权页不存在或无法打开也不会阻断 CarPlay。
2. **准备热点**
   - 有线连接，或无线连接选择“原车热点 / 手动热点”（`MANUAL`）时，尝试开启原车保存的 Wi-Fi 热点。
   - 保留原有 SSID 和密码，不替用户生成新的热点配置。
   - 缺少“修改系统设置”权限时，尝试打开系统授权页。
   - 本次开启请求的超时时间为 12 秒；失败后提示，但不因此阻止后续有线 CarPlay 启动。
   - 无线连接使用现有 Wi-Fi、Wi-Fi Direct 等非 `MANUAL` 模式时，保留所选连接方式的网络管理逻辑，不强制切换原车热点。
3. **检查高德运行状态，必要时启动并返回 CarPlay**
   - 拉起前先查询系统可见的运行进程，同时检查高德车机版 `com.autonavi.amapauto` 和手机版 `com.autonavi.minimap`；通过进程的包列表、完整进程名或 `包名:子进程` 匹配，排除已退出（`IMPORTANCE_GONE`）的记录。
   - 任一版本正在运行（包括可见的后台进程）时，不再发送启动 Intent，不等待 2 秒，也不安排返回 Duo，直接继续 CarPlay。仅检测到子进程也会跳过；这不代表高德界面或物理显示路由一定已就绪。
   - 未检测到运行进程时，优先尝试高德车机版，再尝试手机版。Android 及厂商固件可能限制其他应用的进程可见性：查询为空或失败不等于高德一定未运行，此时回退到当前 Duo 进程只尝试启动一次，不新增使用情况访问、root 或 ADB 授权要求。
   - 启动成功后等待 **2 秒**；若 Duo 不在前台，API 21 及以上先通过 `ActivityManager.appTasks` 找到当前 CarPlay 任务并调用 `moveToFront()`，再用显式 Intent 返回 `CarPlayHostActivity`。
   - 返回 Intent 使用 `NEW_TASK | CLEAR_TOP | SINGLE_TOP`，配合已有 `singleTask` 和独立 CarPlay `taskAffinity` 复用原任务；不返回设置首页，也不销毁高德任务。
   - 返回条件基于“本次启动高德的返回标记尚未消费且当前不在前台”，不依赖“本次剩余等待时间大于零”；预热尚未处理完就重建 Activity 时，即使 2 秒已过去仍会尝试返回。处理预热完成时先清除返回标记，避免后续新界面重放旧的返回动作。
   - 通过 `onResume()` 确认真正回到前台，之后才继续申请 CarPlay 连接权限。每次返回请求发出 1 秒后检查，仍未恢复前台则再等待 2 秒补试；首次返回之外最多补试 3 次，总计最多 4 次。成功恢复前台、Activity 正在退出/已销毁或启动器关闭后停止补试；不能仅凭 `startActivity()` 未抛异常认定返回成功。详见第 12 节。
   - 高德检查/启动尝试在当前应用进程内只进行一次，避免每次重连或返回界面都重新拉起高德；同一个启动器的 `start()` 和预热完成回调也做了防重入。进程被系统杀死后重新启动会重新检查，不持久化“曾经启动过”状态。
   - 未安装或无法启动高德时，记录日志并继续，不阻断 CarPlay。
4. **等待主画面**
   - 主 CarPlay 视频流实际渲染首帧后，才开始创建第二屏镜像输出。
   - 有线、无线和恢复后台会话的路径都处理首帧状态。
   - 从车机桌面返回或重新创建视频 Surface 时，在当前主窗口获得新画面之前保留原连接进度页和等待秒数，不再仅凭“视频流活动”或“该会话曾经出过画面”隐藏进度页。后台音频和连接保持不变，详见第 11 节。
5. **输出第二屏**
   - 只使用系统默认 `Presentation` 窗口类型，不提供或尝试 `TYPE_PHONE / 2002`、`TYPE_SYSTEM_ALERT / 2003`；悬浮窗授权不改变第二屏窗口类型。
   - 首次没有保存目标时，只有一个有效副屏则自动使用；存在多个副屏时，在前台弹窗选择。
   - 保存目标显示器的 ID 和名称；重启后 ID 改变时，允许按唯一同名屏幕恢复。
   - 已保存目标暂时不可用时等待它恢复，不随意改投其他屏幕。
   - 按原视频比例居中显示，空白区域填黑，不拉伸画面。
   - 处理副屏出现、移除、Surface 重建，以及 CarPlay 断线和重连。

> 当前代码的高德预热时间是 **2 秒**。早期实现和历史对话中的“30 秒”已经不是当前行为。固定延时不等同于检测高德或车机物理显示路由已经就绪。

## 3. 镜像方式与兼容边界

参考 `../mirror` 的第二屏 `Presentation + SurfaceView` 和等比黑边布局，但不沿用它的主屏录屏采集链路。

Duo 的输出链路是：

```text
CarPlay 主视频流（110）
  → AndroidMediaSink 的额外镜像解码器
  → 第二屏 Presentation 中的 SurfaceView
```

- 不使用 `MediaProjection`，不需要系统录屏授权页。
- 镜像的是 **CarPlay 主视频画面**，不是 Android 桌面、高德画面、DiPlayDuo 设置页或主屏通知。
- 与独立仪表地图流（111）不是同一功能；镜像开始输出时会让已有仪表投影窗口退出，避免窗口冲突。
- 镜像需要额外视频解码资源，流畅度和解码能力取决于车机硬件与固件。
- 普通应用必须能访问目标 Display；不可访问的私有显示器不能通过本功能绕过系统权限。
- 高德仅通过普通启动 Intent 打开，不会自动开始导航，也没有接入厂商私有的显示路由使能接口。
- 创建窗口成功不等于物理仪表一定可见。仪表显示路由是否被高德维持，仍需在实际车型上确认。
- 热点控制依赖固件接口与权限；旧系统补充了 `setWifiApEnabled` 开启路径，较新系统复用已有热点控制及已授权的 ADB 回退能力，不代表任意 Android 设备都支持静默开启热点。

## 4. 首次安装与使用

1. 安装 Duo APK，确认启动器名称为 **DiPlayDuo**。
2. 在 Duo 中配置连接方式、认证来源和 iPhone 配对；原版配置不会自动迁移。
3. 无线使用原车热点时，先在车机设置确认热点 SSID、密码，再在 Duo 中填写匹配的信息。
4. 进入 CarPlay 时若打开“显示在其他应用上层 / 悬浮窗”授权页，请允许 **DiPlayDuo**，然后返回应用继续。自动热点控制可能另外打开“修改系统设置”授权页；二者不是同一个权限。
5. 进入 CarPlay 启动流程，等待高德预热和主 CarPlay 画面出现。
6. 多副屏时选择正确目标，检查第二屏是否实际显示画面。

Duo 的镜像本身不需要 root 或录屏授权；这不意味着自动热点、其他原项目功能或厂商屏幕访问不再需要各自权限。

## 5. 一键打包脚本

项目根目录提供 `build-diplayduo.sh`：

```bash
cd DiPlay-Legacy-Android-0.2.21

# 默认 Debug 包
bash ./build-diplayduo.sh

# 查看帮助
bash ./build-diplayduo.sh --help
```

支持四种模式：

| 模式 | 命令参数 | Gradle 任务 | 附加要求 |
| --- | --- | --- | --- |
| Debug | `debug`（默认） | `:mobile:assembleDebug` | 本地 Android Debug 签名 |
| Release | `release` | `:mobile:assembleRelease` | 配置 Release 签名 |
| 独立认证 Debug | `standalone-debug` | `:mobile:assembleStandaloneDebug` | 有效本地认证材料 |
| 独立认证 Release | `standalone-release` | `:mobile:assembleStandaloneRelease` | 有效本地认证材料及 Release 签名 |

脚本会定位 JDK 和 SDK、调用对应打包任务，然后读取生成 APK 的元信息，检查包 ID 为 `com.shihab.diplay.duo`、应用名称为 `DiPlayDuo`，并执行 APK 签名校验。检查通过后，导出 APK 和 SHA-256 校验文件到 `dist/`。

这些打包命令不运行单元测试、仪器测试或 lint。`standalone-*` 会执行项目已有的认证文件检查，包括密钥与证书匹配及签名验证；它不是测试套件，也不是实车验证。

### 构建环境

- Gradle Wrapper：9.5.0。
- Gradle Daemon：项目固定为 JDK 25；脚本可定位 Gradle 已下载的 JDK 或 Homebrew JDK。
- Android SDK：API 37；脚本接受 `platforms/android-37` 或 `platforms/android-37.0` 目录。
- Android Build Tools：37.0.0，用于脚本中的 `aapt` 和 `apksigner` 校验。
- Android NDK：25.2.9519653（r25c，项目为兼容 Android 4.4 固定此版本）。

macOS 安装示例：

```bash
brew install --cask android-commandlinetools

export ANDROID_HOME="$HOME/Library/Android/sdk"

sdkmanager --sdk_root="$ANDROID_HOME" \
  "platform-tools" \
  "platforms;android-37.0" \
  "build-tools;37.0.0" \
  "ndk;25.2.9519653"

sdkmanager --sdk_root="$ANDROID_HOME" --licenses
```

按提示阅读并接受 SDK 许可。如 SDK 仓库将 API 37 平台列为 `platforms;android-37`，使用对应包名。

可通过 `JAVA_HOME` 指定本地 JDK。SDK 默认位置为 macOS 的 `~/Library/Android/sdk`，其他系统为 `~/Android/Sdk`；也可用 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT` 指定。若同时设置后两者，必须指向同一个目录。

### 认证材料

普通 `debug` / `release` 不强制要求独立认证材料，但安装成功不等于具备独立 CarPlay 认证能力。需要依据实际部署配置有效认证来源。

当前构建查找本地认证资源的顺序为：

1. 环境变量 `DIPLAY_AUTH_ASSETS_DIR` 指定的目录。
2. 项目内 `.private/runtime-assets/`。
3. 项目内 `auth-assets/`。

**普通模式也会打包找到的本地认证资源**，不能仅凭构建模式判断 APK 是否包含认证材料。发布或分享 APK 前应确认实际输入，不要将私钥、证书或签名密钥提交到公开仓库。

独立认证目录结构：

```text
/path/to/runtime-assets/
└── offline-mfi/
    ├── identity.pk8
    └── certificate.p7b
```

使用你有权使用且匹配的认证材料打包：

```bash
export DIPLAY_AUTH_ASSETS_DIR="/path/to/runtime-assets"
bash ./build-diplayduo.sh standalone-debug
```

### Release 签名

```bash
export ANDROID_KEYSTORE_PATH="/path/to/release.jks"
export ANDROID_KEYSTORE_PASSWORD="你的 keystore 密码"
export ANDROID_KEY_ALIAS="你的密钥别名"
export ANDROID_KEY_PASSWORD="你的密钥密码"

bash ./build-diplayduo.sh release

# 已配置独立认证材料时，也可以构建独立认证 Release 包
bash ./build-diplayduo.sh standalone-release
```

未设置签名文件环境变量时，构建还会查找项目根目录的 `release-signing.jks` 或 `release-signing.keystore`。建议显式配置上述变量，妥善保存签名密钥，不依赖构建文件中的默认口令。

## 6. APK 输出与安装

脚本导出格式：

```text
dist/DiPlayDuo-<版本>-<模式>-<时间戳>-<进程号>.apk
dist/DiPlayDuo-<版本>-<模式>-<时间戳>-<进程号>.apk.sha256
```

Gradle 原始产物：

```text
mobile/build/outputs/apk/debug/mobile-debug.apk
mobile/build/outputs/apk/release/mobile-release.apk
```

截至 2026-11-01，`dist/` 已存在以下产物及对应 `.sha256` 文件：

- `DiPlayDuo-0.2.21-debug-20261101-192205-44714.apk`
- `DiPlayDuo-0.2.21-standalone-debug-20261101-204608-47219.apk`

编写本文时读取了后一个 APK 的包元信息，确认名称为 `DiPlayDuo`、包 ID 为 `com.shihab.diplay.duo`、版本为 `0.2.21` / `41`，最低 API 为 19。文件存在及包元信息正确，不代表已完成实车热点、CarPlay 认证或副屏显示验证；历史 APK 也不保证包含其生成后修改的源码。

安装示例（将文件名替换为本次生成的 APK）：

```bash
adb install "dist/DiPlayDuo-<版本>-<模式>-<时间戳>-<进程号>.apk"
```

更新已安装的 Duo 时使用相同签名的包，并加 `-r`；不会因此覆盖不同包 ID 的原版 DiPlay。

## 7. 常见问题与日志

| 现象 | 检查方向 |
| --- | --- |
| SDK 找不到 | 检查 `ANDROID_HOME`、API 37 平台目录，以及环境变量路径是否一致。 |
| 缺少 Build Tools / NDK | 安装 `build-tools;37.0.0` 和 `ndk;25.2.9519653`，不要随意更换项目固定的 NDK。 |
| Release 缺少签名文件 | 配置 Release 签名环境变量或放置项目约定的本地签名文件。 |
| 独立认证构建失败 | 检查资源目录结构、文件非空、密钥与证书匹配，以及实际采用的资源目录。 |
| APK 可安装但 CarPlay 不连接 | 检查 Duo 自己的权限、配对、认证来源、USB 默认应用和无线配置，不要将安装成功视为连接成功。 |
| 热点未自动开启 | 检查连接模式、“修改系统设置”权限和固件支持情况；不支持时手动开启原车热点。 |
| 高德没有启动 | 检测到高德已运行时会主动跳过，查看 `Gaode already running` 日志；否则检查支持的包名。Android 10 及以上若没有悬浮窗权限，也会跳过高德以保证 CarPlay 继续。 |
| 回到 Duo 后又跳回高德 | 新逻辑在可见的高德进程已运行时不再启动，并对同一进程的启动尝试及预热返回做防重；如果仍发生，采集 `DiPlay-Startup` 和系统 `ActivityTaskManager`（旧系统为 `ActivityManager`）日志，区分 Duo 重启、系统/车机导航自启动和高德自身行为。进程查询不可见时仍可能执行一次启动。 |
| 高德启动后没有返回 Duo | 确认已给 Duo（不是原版）授予悬浮窗权限，并检查车机的后台弹出界面 / 自启动限制；查看 `CarPlay task resumed after Gaode warmup` 或 `CarPlay task did not resume` 日志。必要时从启动器手动打开 Duo 并进入 CarPlay，当前进程不会再次拉起高德。 |
| 主屏有 CarPlay，副屏无画面 | 检查目标 Display 是否可访问、目标选择是否正确、原车显示路由是否开启，以及额外解码器是否正常。 |
| 已保存副屏不再出现 | 程序会等待原目标，不自动改投其他屏幕；检查副屏连接及高德是否让其出现。 |
| Duo 更新时报签名不一致 | 使用与已安装 Duo 相同的签名密钥；不要直接卸载原版来解决 Duo 的签名问题。 |

查看相关日志：

```bash
adb logcat -v time -s DiPlay-Startup DiPlay-Mirror xcertplay-usb
```

分享日志前应移除设备标识、网络信息及其他敏感内容。

## 8. 关键代码索引

- [CarPlayStartup.kt](common/src/main/java/com/shilapi/xcertplay/CarPlayStartup.kt)：悬浮窗授权、热点准备、高德启动、2 秒预热和返回 CarPlay 任务。
- [CarPlaySecondScreen.kt](common/src/main/java/com/shilapi/xcertplay/CarPlaySecondScreen.kt)：目标显示器选择、等比布局和 Surface 生命周期。
- [CarPlayHostActivity.kt](common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt)：启动接入、首帧后启用镜像、后台会话恢复及断线处理。
- [AndroidMediaSink.kt](shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt)：视频流镜像解码器与 Surface 释放等待。
- [CarHotspotTethering.kt](shared/src/main/java/com/shilapi/xcertplay/network/CarHotspotTethering.kt)：热点开启及旧版 Android 兼容路径。
- [CarPlayController.kt](shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt)：首帧事件转发及 UI 重新接入时的状态恢复。
- [CarPlayMediaEngine.kt](shared/src/main/java/com/shilapi/xcertplay/airplay/CarPlayMediaEngine.kt)：仅由主视频流的首帧触发主画面就绪事件。
- [mobile/build.gradle.kts](mobile/build.gradle.kts)：Duo 包 ID、版本、签名及独立认证打包任务。
- [build-diplayduo.sh](build-diplayduo.sh)：构建、APK 元信息与签名检查、导出及校验文件生成。

## 9. 2026-11-02 修改记录与验证

### 修改原因与逻辑

- **缺少悬浮窗权限没有主动引导**：在 `CarPlayStartup.start()` 中增加系统授权页请求，`CarPlayHostActivity` 通过独立 Activity Result 回调继续流程。与热点的“修改系统设置”请求分开处理，不将系统结果码当作已授权的依据。
- **高德打开后停留在高德**：原实现仅在剩余预热时间大于零时发送返回 Intent，且缺少明确的任务栈回前台处理和实际恢复前台的检查。现在先检查后台返回所需授权，再尝试原任务 `moveToFront()` 和带 `NEW_TASK` 的显式返回；以生命周期恢复前台为准，修正预热截止后重建 Activity 时漏掉返回的问题。
- **第二屏只保留默认窗口**：核对 Duo 源码后确认原本没有 2002/2003 选项；这两个模式属于相邻 `mirror-debug` 的诊断界面，不在本次修改范围。Duo 保持默认 `Presentation`，添加代码注释和回归测试，既不尝试这两种系统悬浮窗类型，也不增加窗口类型选择设置。
- **不强行绕过系统限制**：悬浮窗权限只用于改善高德预热后的后台返回条件，不会创建额外的可见悬浮窗。部分新 Android（例如 Android 15 的可见悬浮窗条件）及厂商固件仍可能拦截后台启动；此时日志会提示未恢复前台，需要检查后台弹出界面限制或手动进入 Duo。自动打开系统授权页不等于自动获得权限。

### 回归测试

新增 [CarPlayStartupTest.kt](common/src/test/java/com/shilapi/xcertplay/CarPlayStartupTest.kt) 和 [CarPlaySecondScreenTest.kt](common/src/test/java/com/shilapi/xcertplay/CarPlaySecondScreenTest.kt)，共 9 项测试，覆盖：

- 首次缺权限时先打开授权页，拒绝或授权页不可用时继续 CarPlay；同一进程不重复弹出。
- 已授权不弹页；高德未安装时不额外等待。
- 授权后启动高德，等待 2 秒并携带正确任务标志返回 CarPlay。
- Activity 重建且预热截止时间已过时仍尝试返回；已在前台时不重复启动自身。
- 关闭启动器对象后取消延迟返回。
- 未授予悬浮窗权限时，第二屏仍使用与普通 `Presentation` 相同的默认窗口类型，非 2002/2003。

上述 9 项定向单元测试已通过。完整 CI 检查也已通过（`BUILD SUCCESSFUL`，耗时 4 分 21 秒）：`shared` 共 1047 项测试、1 项跳过，`common` 共 903 项测试，`home` 共 4 项测试；均无失败或错误。三个应用的 Debug lint 和 Debug APK 构建均成功。执行命令如下（本地 `gradlew` 没有可执行位，因此使用 `bash ./gradlew`，任务与项目 CI 要求一致）：

```bash
bash ./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug
```

本次通过 `bash ./build-diplayduo.sh debug` 导出包含上述修改的 APK：

- APK：`dist/DiPlayDuo-0.2.21-debug-20261102-104447-68319.apk`（约 18.91 MiB）。
- SHA-256：同路径追加 `.sha256`。
- 已核对应用名称、Duo 包 ID，并通过 APK v1 / v2 签名校验；同时检查 Dex 中包含本次新增授权和返回状态的日志标记，避免误用历史 APK。
- 此产物为普通 Debug 包，不是 `standalone-debug`；独立 CarPlay 认证仍取决于实际配置的有效认证材料。

实车待验：首次授权后高德自动返回、拒绝授权后直达 CarPlay、后台弹出权限受限时的手动恢复，以及仪表第二屏物理显示。Robolectric 测试不能模拟厂商后台启动策略或证明物理仪表路由正常。

## 10. 2026-11-02 高德重复跳转防护（后续修改）

### 问题与处理

针对“高德能够自动拉起，但偶尔返回 Duo 后又切回高德”的反馈，本次增加以下防护。仅凭源码不能断定实车上再次切回高德的唯一原因，也不能排除车机导航自启动或高德自身延迟打开界面的行为。

1. **先检查是否已运行**：在当前 Duo 进程的首次高德预热尝试中，通过 `ActivityManager.runningAppProcesses` 检查两个支持的高德包。匹配 `pkgList`、完整进程名和带冒号的子进程名；类似 `com.autonavi.amapauto.other` 不算高德，已退出的进程记录不算运行。检测到任一版本运行就直接继续 CarPlay，避免将已有后台高德再次切到前台。
2. **启动流程防重入**：同一个 `CarPlayStartup` 对象的 `start()` 只接受一次，预热完成回调也检查 `ready`，避免重复排队和重复执行后续启动步骤。进程级 `gaodeAttempted` 继续保证重连或 Activity 重建不再次发出高德启动 Intent。
3. **返回标记只消费一次**：原代码一直保留非零的 `gaodeReadyAt`，后续后台创建的新启动器仍可能重放旧的返回动作。现在在处理预热完成时先将它清零，再按是否在前台决定要不要返回。尚未处理完成就销毁旧 Activity 时，`close()` 只取消旧回调，不清除待完成的截止时间，由新 Activity 接续，仍支持截止时间已过的恢复。
4. **保持兼容回退**：查询失败或系统隐藏高德进程时，仍按原逻辑最多启动一次并等待 2 秒，不轮询、不反复重试；不增加权限请求，不把“进程存在”当作导航、界面或仪表路由就绪证明。Android 10 及以上的悬浮窗返回条件不变。

### 验证与产物

扩展 [CarPlayStartupTest.kt](common/src/test/java/com/shilapi/xcertplay/CarPlayStartupTest.kt)，新增覆盖车机版/手机版已运行、只有子进程、相似包名前缀、已退出进程、Android 6 的运行检查、重复 `start()`、已完成预热不重放返回，以及未完成预热的 Activity 交接。

- 定向测试首次因终端未设置 `JAVA_HOME` 未能启动，后显式使用本地 JDK 25。
- 初次运行中 16 项通过，额外的 API 19 用例因当前 Robolectric 不支持该 SDK（`UnknownSdk`）无法执行；该用例改为 API 23。最低 API 19 兼容性仍需实机验证，不把 API 23 的通过视为 Android 4.4 已验证。
- 本节修改的最终完整 CI 检查已通过（`BUILD SUCCESSFUL`，耗时 4 分 17 秒）：执行第 9 节列出的全部单元测试、三个应用的 Debug lint 和 Debug APK 构建任务。
- 本节和第 11 节修改已一起导出为 `dist/DiPlayDuo-0.2.21-debug-20261102-115635-70896.apk`，详情见第 11 节；第 9 节的历史 APK 不包含这些后续修改。

实车建议验证：先手动启动高德再进入 Duo（应看到 `Gaode already running` 且无切屏）；高德未运行时进入 Duo（应仅拉起一次、2 秒后返回）；连续重连和退出重进 CarPlay（同一进程不再拉起）；Duo 被系统结束后重新进入（重新检测运行状态）。如果仍发生跳转，保留包含系统 Activity 启动来源的日志再定位，不以单元测试代替实车结论。

## 11. 2026-11-02 后台返回期间显示连接进度

### 问题与修改逻辑

反馈：CarPlay 返回车机桌面后音乐继续播放，但再次打开 Duo 时，视频恢复前会出现约 20 秒黑屏。后台音乐继续播放是正常行为，本次不为显示进度而断开或重建会话。

- **界面隐藏条件修正**：原 `updateDebugOverlays()` 只看是否存在活动的视频流，主屏或仪表流活动都会让连接面板消失。后台会话的流状态和历史首帧仍可能有效，但新主窗口尚无画面。现在独立跟踪当前窗口的画面状态；后台停止、Surface 销毁、主流停止或重连时重置，等待期间显示原连接界面、当前阶段和已等待秒数。
- **不把历史首帧当作本次恢复**：保留 `CarPlayController` 原有会话首帧和第二屏镜像逻辑；重新接入 UI 时重放的 `onVideoFrameRendered()` 不会隐藏等待界面。
- **分输出方式确认**：`TextureView` 使用当前纹理的内容更新回调，并检查主屏流已活动。`SurfaceView` 通过 `AndroidMediaSink.awaitMainSurfaceFrame()` 等待当前主展示 Surface 的新帧提交；仅主展示解码器可消费一次性等待，仪表流、第二屏镜像和离屏 parking 输出不能完成等待。平滑视频按本帧计划显示时间补足延时后再隐藏面板。
- **过期回调防护**：按 Surface、sink 和 UI 等待代数校验；旧 Surface、已停止窗口或上一轮等待的迟到回调不能关闭新一轮进度页。同一个 Surface 可以重新等待，不要求重建整个会话。
- **恢复静止画面**：重新接入输出时请求当前主流的新关键帧，避免静止画面没有自然帧而一直等待；扩展到 TextureView、直接 SurfaceView 和平滑视频，不改变音频焦点或后台会话归属。
- **不伪造恢复进度**：复用已有的 `Opening CarPlay…` 等本地化阶段文案和等待秒数，不生成虚假的百分比，也不在固定 20 秒后强行隐藏。阶段计时改用 `SystemClock.elapsedRealtime()` 单调时钟，避免车机校时导致秒数跳动。此修改改善等待界面，不承诺消除实车的视频恢复延迟。

`SurfaceView` 的完成证据是解码器成功向目标 Surface **提交**新帧，并等待已知的平滑视频计划延迟；不是厂商物理屏幕已显示的确认。`TextureView` 的内容更新也不等于物理显示路由保证。实车驱动异常或始终没有新帧时，继续保留进度提示。

### 验证与产物

新增 [CarPlayResumeProgressTest.kt](common/src/test/java/com/shilapi/xcertplay/CarPlayResumeProgressTest.kt) 和 [MainSurfaceFrameWaiterTest.kt](shared/src/test/java/com/shilapi/xcertplay/media/MainSurfaceFrameWaiterTest.kt)，覆盖历史会话首帧、仅仪表流、当前纹理更新、等待 20 秒仍无画面、平滑延时、旧输出/旧回调、停止后同一 Surface 恢复、主流停止、阶段计时，以及等待器的重复使用、取消、关闭和解码器路由隔离。

- 定向回归已通过：本轮新增等待器测试 5 项、恢复进度测试 7 项，以及高德启动、Surface 模式、平滑视频生命周期、准备界面布局的既有测试，共 `shared` 5 项和 `common` 35 项。首轮发现阶段计时使用墙上时钟的问题，改用单调时钟后重跑成功（`BUILD SUCCESSFUL`）。
- 另在 `AndroidMediaSinkCodecDetachTest` 新增实际走 ShadowMediaCodec 的提交/parking/恢复回归，检查同一会话、同一 Surface 和同一个 codec 在恢复后仍能再次通知，不重置历史首帧标记。
- 完整 CI 检查已通过（`BUILD SUCCESSFUL`，耗时 4 分 47 秒），包含上述 codec 集成回归：`shared` 1053 项（1 项跳过）、`common` 919 项、`home` 4 项，无失败或错误；三个应用的 Debug lint 和 Debug APK 构建均成功。
- 已通过 `bash ./build-diplayduo.sh debug` 导出包含第 10、11 节修改的 APK：[DiPlayDuo-0.2.21-debug-20261102-115635-70896.apk](dist/DiPlayDuo-0.2.21-debug-20261102-115635-70896.apk)，大小 19,829,390 字节（约 18.91 MiB）。
- 已检查 Duo 包 ID 和应用名称，APK v1 / v2 签名校验通过。签名工具同时报告部分 `META-INF` 元数据条目不受 JAR 签名保护的警告，整体 APK 校验结果为 `Verifies`。
- [SHA-256 校验文件](dist/DiPlayDuo-0.2.21-debug-20261102-115635-70896.apk.sha256)：`a8db9c48fddb61024460623897ca750ed97ff1fb08b9137783169ab7b842eb66`。
- 此为普通 Debug 包，不是 `standalone-debug`；独立 CarPlay 认证仍取决于实际配置的有效认证材料。历史 APK 不包含本节修改。

实车待验：播放音乐后返回桌面，再从启动器打开 Duo；恢复期间应有连接阶段和等待时间，音乐不断，画面恢复后自动隐藏面板。分别验证普通画面、直接 SurfaceView、平滑视频，以及反复切换桌面、静止画面和 Activity 被系统重建的情况。

## 12. 2026-11-02 开机高德预热返回的有限重试

### 问题与兼容处理

反馈：车机开机自启 Duo 并拉起高德后无法自动返回；系统启动稳定后，手动关闭高德、打开 Duo 的场景可以返回。原实现只有一次返回请求，1 秒后未恢复前台仅记录警告，没有后续恢复动作。开机后台启动限制或冷启动时序差异可能导致该现象，实际系统拒绝原因仍需现场日志确认。

本次在 `CarPlayStartup` 中增加有限重试，不重复启动高德，不绕过系统权限：

- 高德预热仍等待 2 秒，然后按原逻辑首次尝试返回 CarPlay（原任务 `moveToFront()` + 显式返回 Intent）。
- 每次请求返回后等待 1 秒，若仍未收到 `onResume()`，记录日志并再等待 2 秒补试。
- **首次尝试 + 最多 3 次补试，共最多 4 次返回请求**。从高德启动请求计时，理想调度时约在第 2、5、8、11 秒请求返回；第 12 秒仍未恢复则停止并提示检查后台启动权限、手动打开 Duo。主线程调度和系统负载可能延后这些时间，不保证严格按秒发生。
- 每次补试仍使用原任务和相同 Intent 标志，不创建新的高德任务；`onReady` 仍只调用一次，CarPlay 后续权限申请继续由前台生命周期约束，不随补试重复执行。
- 任何一次 `onResume()` 确认恢复即结束本轮补试。之后用户离开 Duo 不会重新触发这轮返回，避免反复抢前台；若高德在成功返回后再次抢前台，本次逻辑不会持续夺回。
- Activity 正在退出或已销毁时不再返回；`close()` 会取消检查和补试回调。既有预热交接逻辑不变，但已经开始返回后的补试计数/回调属于当前启动器，不跨 Activity 销毁或应用进程退出持久化。
- 若系统始终禁止后台弹出，有限补试仍可能全部失败，需要检查 Duo 的悬浮窗/后台弹出权限或手动进入应用，不将重试视为权限豁免。

### 日志与验证

`DiPlay-Startup` 新增返回次数日志 `Returning to CarPlay after Gaode warmup; attempt=1/4`（后续递增）；失败检查打印 `CarPlay task did not resume; retrying in 2 seconds`；全部失败打印 `CarPlay task did not resume after 4 attempts`。成功仍以 `CarPlay task resumed after Gaode warmup` 为准。

在 `CarPlayStartupTest.kt` 新增 7 项回归用例，覆盖 1 秒检查/额外 2 秒等待、补试上限、检查前恢复、补试等待期间恢复、补试后恢复、关闭取消，以及 Activity 退出/销毁时停止。恢复用例额外检查成功后再次暂停不会重放返回，补试不会重复调用就绪回调。

本轮已尝试运行定向回归和第 9 节的完整 CI 检查，但两次 Gradle 启动均因本机缺少可用 Java 运行时而失败，输出为 `Unable to locate a Java Runtime`，未执行到编译、测试、lint 或 APK 构建。上述历史章节的通过记录不适用于本轮修改；本轮未生成新 APK。安装/配置项目要求的 JDK 25 和 Android SDK 后需重新执行：

```bash
bash ./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.CarPlayStartupTest
bash ./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug
```

实车待验：开机自启后首次返回失败、后续补试成功；手动打开时首次返回成功且无额外跳转；后台弹出始终受限时最多 4 次并停止；成功返回后主动切换应用不会被本轮补试拉回。单元测试不能证明厂商后台策略或实际冷启动问题已解决。
