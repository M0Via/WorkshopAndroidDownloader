# 扫码登录 + 一键分享二维码 —— 集成说明

本文档记录为 `WorkshopOnAndroid`（Apricityx/WorkshopAndroidDownloader）新增的两项能力：

1. **Steam 扫码登录**（QR Auth Session）——原项目只支持「账号密码 + Guard 码」和「粘贴 Refresh Token」，本次补上第三条原生路径。
2. **一键分享二维码**——把二维码图片通过系统分享面板发给朋友，由朋友用手机 Steam App 扫码授权。

设计严格贴合原项目架构：复用现有 CM 会话、轮询协议、token 持久化、FileProvider 分享机制，不引入平行的第二套体系。

---

## 一、设计依据（为什么这样改）

吃透原代码后得出的三条关键事实，决定了改动形态：

| 事实 | 依据 | 对设计的意义 |
|---|---|---|
| 密码登录与扫码登录「拿 token」阶段完全同构 | 两者最终都靠 `clientId + requestId` 调 `Authentication.PollAuthSessionStatus#1` 拿到 refreshToken | 只新增「Begin」阶段，轮询与持久化 100% 复用 |
| proto 已预留 QR 所需字段 | `PollAuthSessionStatus_Response` 已有 `new_challenge_url`（二维码刷新） | 无需大改 proto，只补 `BeginAuthSessionViaQR` 请求/响应 |
| Steam 二维码会周期性刷新 | `new_challenge_url` 字段存在即为证（PC 端实测旧帧会「加载失败」） | 会话内维护最新 URL，刷新时回调 UI 重绘 + 重生成分享图 |

关于「仅授权一次」：**不做任何额外的凭据清除逻辑**。Steam 在手机端确认登录时，原生提供授权范围/时效选项，由用户在 Steam App 内自行选择（含一次性授权）。App 侧尊重 Steam 原生行为，不越权干预。

---

## 二、改动清单

### 协议层 `steam-protocol`（纯 JVM，已单独编译通过）

| 文件 | 改动 |
|---|---|
| `proto/steam_messages.proto` | 新增 `CAuthentication_BeginAuthSessionViaQR_Request / Response`（含 challenge_url / client_id / request_id / interval） |
| `Models.kt` | 新增 `SteamQrAuthSessionDetails`；新增统一契约 `SteamAuthSession : Closeable`（`suspend fun awaitResult()`），密码/二维码会话都实现它 |
| `SteamAuthenticationClient.kt` | 新增 `beginQrAuthSession()`；新增 `SteamQrAuthSession`（持有 challengeUrl、刷新回调、复用 PollAuthSessionStatus 轮询）；`SteamCredentialAuthSession` 改为实现 `SteamAuthSession` |

### 应用层 `app`

| 文件 | 改动 |
|---|---|
| `QrLoginShareManager.kt`（新增） | ZXing 生成二维码 Bitmap → 写入 `filesDir/exports/` → FileProvider content Uri → `ACTION_SEND` 分享 Intent |
| `SteamAuthRepository.kt` | `pendingAuthSession` 类型改为 `SteamAuthSession`；新增 `SteamSignInStep.QrCodeReady`；新增 `beginQrSignIn()` / `waitForQrConfirmation()`；持久化走原有 `finalizePendingAuth` → `persistAccount` |
| `WorkshopUiState.kt` | `SteamLoginInputMode` 加 `QrCode`；`SteamLoginDialogUiState` 加 `qrChallengeUrl / qrShareUri / qrFrameSeq` |
| `WorkshopViewModel.kt` | 新增 `startQrLogin()`、`refreshQrShareArtifacts()`；`startSteamConfirmationWait` 支持注入等待动作；QR 轮询复用原等待 job |
| `SettingsScreen.kt` | 登录对话框新增「扫码登录」模式：二维码显示 + 一键分享按钮 + 自动刷新提示 |
| `MainActivity / WorkshopScreen / WorkshopScreenSections` | 回调链新增 `onStartQrLogin`（机械传递） |
| `libs.versions.toml` / `app/build.gradle.kts` | 新增 `com.google.zxing:core`（纯 Java，minSdk 26 兼容） |

> 注：本提交仅包含 QR 扫码登录功能相关文件；上一轮「PC 端下载工具」的临时产物（CLI 入口与构建任务）未纳入本次提交。

---

## 三、用户使用流程

1. 设置页 → 登录 Steam → 登录对话框点「改用扫码登录」
2. 点「生成登录二维码」→ 显示二维码，后台开始等待手机确认
3. 两种方式授权：
   - **本人扫**：手机 Steam App 扫屏幕上的二维码
   - **朋友代扫**：点「一键分享二维码给朋友扫码」→ 系统分享面板发给微信/QQ → 朋友扫码授权
4. 手机端确认登录（授权范围/时效在 Steam 手机上选，可仅授权一次）
5. App 轮询拿到 refreshToken → 自动走原有持久化 → 登录成功，对话框关闭

二维码过期自动刷新（URL 变化时 UI 重绘，分享图片同步更新为最新帧），无需手动干预。

---

## 四、编译与验证（全部通过）

- 协议层 + 核心层：`./gradlew :steam-protocol:compileKotlin :workshop-core:compileKotlin` —— ✅ BUILD SUCCESSFUL
- 应用层：`./gradlew :app:compileDebugKotlin` —— ✅ BUILD SUCCESSFUL
- 完整打包：`./gradlew :app:assembleDebug` —— ✅ BUILD SUCCESSFUL
- 产物：`WorkshopOnAndroid-QR-debug.apk`（25MB，包名 `top.apricityx.workshop`，minSdk 26 / targetSdk 36，可直接 adb install）
- 环境：JDK21 + Android SDK（platform 36 / build-tools 36.0.0），`local.properties` 中 `sdk.dir` 指向你本机的 Android SDK 路径

## 五、待办 / 可扩展

- 分享出去的二维码是「登录本 App 会话」用的，任何扫码者都在自己的手机上确认自己的账号授权——即「朋友用他的 Steam 账号帮我这台设备授权」。若需限制只有特定账号可授权，需在 `beginQrSignIn` 前置账号白名单校验。
- 二维码 PNG 落在 `filesDir/exports/steam_login_qr.png`，随 App 卸载清除；取消登录时可主动删除。
- 若要上架 Google Play，`com.google.zxing:core` 需确认许可（Apache 2.0，兼容）。
