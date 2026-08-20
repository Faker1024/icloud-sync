# iCloud 中国区云盘客户端：开发设计文档

> 文档状态：原生 UI / 私有 Web 接口 MVP<br>
> 最后更新：2026-08-21<br>
> 当前版本：0.3.0

## 1. 产品定义

本项目是在 Android 上运行的第三方 iCloud Drive 客户端，服务于中国大陆 Apple 账户。登录、双重认证、文件夹浏览、文件列表和下载按钮全部由 App 的 Jetpack Compose UI 提供；App 不嵌入 WebView，也不依赖浏览器登录状态。

由于 Apple 没有向 Android 第三方 App 提供读取用户整个 iCloud Drive 的公开 API，底层使用 iCloud.com 中国区所调用的未公开 Web 接口。该接口没有稳定性、兼容性或上架许可承诺，必须把它视为可替换的实验性适配层。

### 1.1 MVP 目标

- 仅连接 iCloud 中国区身份、Setup、Drive 和 Document 服务。
- 通过原生表单完成 Apple 账户登录和双重认证。
- 查看整个 iCloud Drive 文件夹树及任意文件类型。
- 用户主动下载文件到 `Download/iCloud Drive/`。
- 密码不落盘、不明文发送；会话令牌和 Cookie 加密保存。
- 保留照片、视频或 ZIP 到系统相册的可选导入功能。

### 1.2 非目标

- 不提供后台增量扫描、实时同步或双向同步。
- 不上传、改名、移动或删除云端文件。
- 不支持多账户同时在线。
- 不经过开发者服务器中转账户或文件数据。
- 不尝试绕过 Apple 的双重认证、条款确认、账户锁定或设备授权。
- 当前版本不处理高级数据保护（ADP）的 PCS 授权流程。

## 2. 用户流程

```mermaid
flowchart LR
    A[原生登录 UI] --> B[设备端 SRP 证明]
    B --> C{需要双重认证}
    C -->|是| D[原生验证码 UI]
    C -->|否| E[accountLogin]
    D --> E
    E --> F[原生文件夹列表]
    F --> G[获取 Document 下载地址]
    G --> H[域名与 HTTPS 校验]
    H --> I[Android DownloadManager]
    I --> J[Download/iCloud Drive]
    J --> K{用户要导入媒体}
    K -->|是| L[SAF + WorkManager + MediaStore]
```

1. App 启动后尝试解密本机会话；存在会话时直接读取根目录。
2. 没有会话时显示 App 自己的账号密码表单。
3. 客户端使用 SRP-6a 生成登录证明，密码不会放进 HTTP 请求体。
4. 如果 Apple 返回双重认证挑战，显示 6 位验证码界面，并支持受信任设备推送和短信。
5. 登录完成后取得 `drivews` 和 `docws` 服务地址，读取根目录。
6. 点击文件夹时读取子项；点击文件时取得短期下载地址。
7. 下载地址通过 HTTPS 和 Apple 域名白名单检查后交给系统下载服务。
8. 通用文件保留在 Downloads；媒体只有在用户主动选择后才写入 DCIM。

## 3. 技术基线与结构

| 项目 | 决策 |
| --- | --- |
| 语言与 UI | Kotlin、Jetpack Compose、Material 3 |
| 状态管理 | MVVM、Coroutines、StateFlow |
| HTTP | OkHttp，独立 CookieJar |
| JSON | Android `org.json` |
| 认证密码学 | SRP-6a、RFC 5054 2048-bit group、SHA-256、PBKDF2-HMAC-SHA256 |
| 会话加密 | Android Keystore AES-256-GCM |
| 通用下载 | Android DownloadManager |
| 媒体导入 | SAF、WorkManager、MediaStore |
| 本地数据 | Room、DataStore |
| 注入 | Hilt |
| Android | minSdk 29、compileSdk/targetSdk 36、JDK 17 |

核心目录：

```text
core/icloud/
├── AppleSrp.kt               # SRP 公钥、派生密钥和 M1/M2 证明
├── ICloudApiClient.kt        # 中国区认证、2FA、Drive、Document 请求
├── ICloudCookieJar.kt        # 进程内 Cookie 域匹配与快照
├── ICloudSessionStore.kt     # Keystore 加密会话
├── ICloudDriveRepository.kt  # IO 调度和下载任务
└── ICloudModels.kt

feature/main/
├── CloudDriveViewModel.kt    # 登录/2FA/目录/下载状态机
└── CloudDrivePage.kt         # 完全原生 Compose UI

core/web/
└── CloudDriveDownloads.kt    # 仅保留系统下载与文件名/域名校验
```

## 4. 中国区认证协议

### 4.1 固定入口

```text
身份认证：https://idmsa.apple.com.cn/appleauth/auth
主页来源：https://www.icloud.com.cn
账户服务：https://setup.icloud.com.cn/setup/ws/1
```

不自动回退到国际区。`accountLogin` 返回 `domainToUse` 且不是中国区时，应提示账户区域不匹配。

### 4.2 SRP 登录

认证顺序：

1. `GET /authorize/signin` 初始化 Apple 登录会话。
2. `POST /federate` 提交规范化为小写的 Apple 账户名。
3. 生成 32 字节随机私钥 `a`，计算并提交 256 字节填充的 `A = g^a mod N`。
4. `POST /signin/init` 获取 `salt`、`iteration`、`protocol`、`B` 和挑战 `c`。
5. 根据协议派生密码材料：
   - `s2k = PBKDF2(SHA256(password), salt, iteration)`；
   - `s2k_fo = PBKDF2(hex(SHA256(password)), salt, iteration)`。
6. 按 Apple 的 `NoUserNameInX` 变体计算共享密钥和 M1/M2。
7. `POST /signin/complete` 提交证明，不提交密码。

必须拒绝 `B <= 0`、`B >= N`、`u = 0`、未知协议和异常字段长度。任何日志都不得包含账户名、密码、派生密钥、证明、Cookie 或 Apple 会话响应正文。

### 4.3 双重认证

- `409`：需要双重认证。
- `PUT /verify/trusteddevice/securitycode`：主动向受信任设备请求验证码。
- `POST /verify/trusteddevice/securitycode`：验证受信任设备验证码。
- `PUT /verify/phone` 与 `POST /verify/phone/securitycode`：短信发送与验证。
- `GET /2sv/trust`：将本机会话设为受信任。

2026 年起，验证码端点可能在验证成功时仍返回 HTTP 409。只有同一响应同时携带新的 `X-Apple-Session-Token` 才能判定为成功；单独 409 必须判定为验证码错误。

### 4.4 账户会话

完成认证后调用 `/accountLogin`，请求包含 `accountCountryCode`、`dsWebAuthToken`、`extended_login` 和 `trustToken`。只提取 `drivews`、`docws` 和 `pcsRequired` 等必要字段。

保存字段包括会话令牌、信任令牌、会话 ID、认证属性、服务地址和 Cookie。整个 JSON 使用 Android Keystore 中不可导出的 AES 密钥以 GCM 模式加密。Apple 账户与会话一起加密；密码和验证码永不进入会话对象。

## 5. Drive 浏览和下载

### 5.1 根目录与列表

根目录 ID：

```text
FOLDER::com.apple.CloudDocs::root
```

读取文件夹使用 `drivews/retrieveItemDetailsInFolders`。请求只提交文件夹 `drivewsid`，响应映射为稳定的 UI 模型：ID、名称、类型、大小、修改时间和子项数量。

文件夹类型为 `FOLDER`、`APP_CONTAINER` 或 `APP_LIBRARY`。列表先显示文件夹，再按本地语言环境对名称排序。UI 维护面包屑路径，Android 返回键逐级返回父目录。

### 5.2 下载票据

从 `drivewsid` 提取 zone 和 document ID，然后调用：

```text
docws/ws/{zone}/download/by_id?document_id={id}
```

优先使用 `data_token.url`，否则使用 `package_token.url`。下载地址必须为 HTTPS，且主机是以下精确域或子域之一：

- `icloud.com.cn`、`icloud-content.com.cn`
- `icloud.com`、`icloud-content.com`
- `apple-cloudkit.com`、`apzones.com`、`cdn-apple.com`
- `apple.com.cn`、`apple.com`

类似 `icloud.com.cn.example.com` 的主机必须拒绝。CookieJar 只向匹配下载 URL 域、路径和 Secure 属性的 Cookie 生成请求头。

### 5.3 保存位置

所有原始文件默认保存到：

```text
Download/iCloud Drive/
```

理由是云盘包含文档、归档和项目文件，不能把所有内容写入 DCIM。同名文件生成 `name (2).ext`，文件名移除路径分隔符、控制字符和保留字符，最大长度 180。

## 6. UI 状态机

| 状态 | UI |
| --- | --- |
| `RESTORING` | 恢复加密会话进度 |
| `LOGGED_OUT` | 账户、密码、风险确认和登录按钮 |
| `AUTHENTICATING` | 禁用输入并显示进度 |
| `TWO_FACTOR` | 6 位验证码、设备推送、短信和取消 |
| `BROWSING` | 面包屑、文件列表、刷新、下载和退出 |

网络调用全部在 `Dispatchers.IO`；UI 不持有 Cookie、令牌或 HTTP 响应。密码从 Composable 传给 ViewModel 后立即清空输入状态。下载中的文件 ID 单独记录，避免重复点击创建多个系统任务。

## 7. 错误模型

| 错误 | 用户提示与处理 |
| --- | --- |
| 网络不可用 | 保留当前页面，允许重试 |
| 账户或密码错误 | 清空会话，返回登录页 |
| 验证码错误或过期 | 留在 2FA 页面，可重发 |
| 401 / 421 | 会话过期，清除并重新登录 |
| 423 / `pcsRequired` | 提示高级数据保护暂不支持 |
| 429 | 提示稍后重试，不自动高频重放 |
| 5xx | 提示 iCloud 服务暂不可用 |
| JSON/服务地址异常 | 拒绝使用返回值，显示兼容性错误 |
| `domainToUse` 不匹配 | 提示必须使用中国大陆 iCloud 账户 |

异常消息不得拼接 Apple 原始响应正文，防止服务端返回的个人信息或令牌进入日志/UI。

## 8. 可选媒体导入

云盘下载与相册导入是两个独立流程。用户通过 SAF 主动选择下载后的图片、视频或 ZIP，WorkManager 才执行：

1. 把输入流式暂存到 App 私有缓存并检查空间。
2. ZIP 拒绝绝对路径、`..` 路径逃逸、加密包和超过 5,000 个条目的归档。
3. 结合文件签名、MIME 和扩展名识别 JPEG、HEIC/HEIF、PNG、GIF、DNG/RAW、MP4、MOV。
4. 计算 `SHA-256 + 字节数`，跳过精确重复。
5. 使用 `IS_PENDING=1` 原子写入 `DCIM/iCloud Photos/`，成功后发布。
6. Room 保存批次、文件级状态和错误码；任务可取消并可从检查点恢复。

通用 PDF、Office、压缩包等不得进入 MediaStore 图片/视频集合。

## 9. 安全与隐私

- 仅声明网络、用户发起任务通知和必要前台服务权限，不申请“所有文件访问”或全相册读取权限。
- Manifest 禁止明文 HTTP，App 备份关闭，会话不进入云备份。
- 服务发现结果必须重新执行 HTTPS 和域名白名单校验。
- OkHttp 不安装网络日志拦截器；崩溃报告不得记录请求头、请求体或响应正文。
- 退出登录清除内存 CookieJar 和加密偏好；已下载文件不删除。
- 不使用证书校验绕过、自签名信任或宽松 HostnameVerifier。
- UI 明示私有接口风险、第三方身份和数据只在本机处理。

## 10. 测试策略

### 10.1 自动化测试

- SRP `s2k`、`s2k_fo` 独立 PBKDF2 向量。
- 固定私钥、固定 B 的 M1/M2 独立证明向量。
- 拒绝非法 SRP B 和未知协议。
- 下载域名精确后缀校验和相似域名绕过。
- 文件名清理、长度和同名策略。
- 原有 ZIP 路径、媒体识别、哈希与设置测试。
- Debug 单元测试、Lint 和 APK 构建。

### 10.2 真机集成测试

自动化环境不能携带真实 Apple 凭据。发布前必须使用测试专用中国大陆账户，在至少 Android 10、13、15/16 和三星/小米/OPPO/vivo 设备覆盖：

- 首次登录、错误密码、账户锁定提示。
- 受信任设备验证码、错误码、过期码、重发和短信。
- 进程被杀后恢复加密会话、会话到期后重登。
- 根目录、深层目录、空目录、中文/Emoji/超长文件名。
- PDF、Office、ZIP、图片、视频和 iWork package 下载。
- Wi-Fi/蜂窝网络切换、断网、空间不足和重复点击。
- 中国区条款未确认、iCloud Drive 未开启和 ADP 账户提示。

测试账户不得包含真实个人照片、联系人、位置或生产文件；凭据不得写入仓库、Gradle 属性、CI Secret 输出或截图。

## 11. MVP 验收标准

- App 中不存在 WebView，所有登录与文件 UI 都是原生 Compose。
- 中国大陆测试账户可完成 SRP 登录、2FA 和根目录读取。
- 可逐层浏览文件夹，并下载至少文档、压缩包、照片和视频。
- 原始文件出现在 `Download/iCloud Drive/` 且内容哈希与云端一致。
- 密码/验证码不落盘；重启后仅通过 Keystore 加密会话恢复。
- 非 HTTPS、白名单外域名和相似域名下载均被拒绝。
- 退出登录后不能继续读取云盘，已下载文件保留。
- 单元测试、Lint、Debug 构建通过；真机测试结果记录在发布清单。

## 12. 兼容与发布说明

私有接口适配层需要独立版本管理。发现 Apple 改动时优先更新请求头、状态码兼容和 JSON 映射，不让协议字段泄漏到 UI。若维护成本或账户风险不可接受，应停用登录入口并提示升级，而不是回退到明文密码认证。

应用商店文案不得声称“Apple 官方”“永久兼容”或“实时同步”。隐私政策必须说明 App 接收用户输入的 Apple 账户和密码用于设备端 SRP、会加密保存会话、使用私有 Web 接口，以及开发者服务器不接收这些数据。

## 13. 参考

- [Apple：在 iCloud.com 上查看和下载 iCloud Drive 文件](https://support.apple.com/guide/icloud/view-and-download-files-mmbdb4c6b10f/icloud)
- [Android：DownloadManager](https://developer.android.com/reference/android/app/DownloadManager)
- [Android：Keystore](https://developer.android.com/privacy-and-security/keystore)
- [Android：Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)
- [Android：MediaStore](https://developer.android.com/training/data-storage/shared/media)
- [rclone：iCloud Drive backend](https://github.com/rclone/rclone/tree/master/backend/iclouddrive)
- [pyicloud：China domain endpoint implementation](https://github.com/picklepete/pyicloud)

协议实现参考 rclone 的 MIT 许可代码并重新以 Kotlin 编写，详见仓库根目录 `NOTICE`。
