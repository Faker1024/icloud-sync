# iCloud 中国区云盘下载助手

一款面向 Android 和中国大陆 Apple 账户的 iCloud 云盘网页浏览与文件下载助手。

用户可以在 App 内嵌的中国区官方 `https://www.icloud.com.cn/iclouddrive/` 页面完成登录、浏览整个 iCloud 云盘并下载任意类型文件。下载内容按原格式保存到 Android 公共目录 `Download/iCloud Drive/`。中国大陆 iCloud 由云上贵州运营。

## 项目状态

MVP 已完成首个可构建版本，当前实现包括：

- 在 App 内使用受限 WebView 打开 iCloud 中国区云盘网页。
- 浏览云盘目录并下载文档、压缩包、照片、视频等任意文件。
- 使用 Android 系统 DownloadManager 保存到 `Download/iCloud Drive/`。
- 对顶层导航和下载地址执行 HTTPS 与 Apple 域名白名单检查。
- 支持返回、前进、刷新、云盘首页、系统下载列表和清除登录数据。
- 通过 Storage Access Framework 选择 ZIP、照片或视频。
- 本地暂存、存储空间预检和可取消的后台导入。
- ZIP 路径安全检查、条目数量限制和流式解压。
- JPEG、HEIC/HEIF、PNG、GIF、常见 RAW、MOV、MP4 识别。
- SHA-256 精确去重和同名文件保护。
- 使用 MediaStore 原子写入 `DCIM/iCloud Photos/`。
- Room 导入历史、进度、失败状态和相册名称设置。
- Android 10～16 构建基线，无广泛照片或全部文件权限。

## 产品边界

- 不接入非公开 iCloud Drive 或 iCloud Photos API。
- 不跳转到国际区 `www.icloud.com` 登录入口。
- 不读取或保存 Apple 账户密码和验证码。
- 不注入或自动操作 iCloud 网页。
- 不提供实时或后台 iCloud 同步。
- 下载时仅将当前 WebView 会话 Cookie 交给 Android 系统下载服务，用于访问用户主动选择的文件。
- 文件下载和媒体处理均在用户设备上完成，不经过开发者服务器。

## 开发文档

完整的产品边界、技术架构、数据模型、导入流程、安全设计、测试方案和开发里程碑见 [开发设计文档](docs/DEVELOPMENT.md)。

## 计划技术栈

- Kotlin
- Jetpack Compose + Material 3
- Room + DataStore
- Kotlin Coroutines + Flow
- Android WebView
- Android DownloadManager
- Storage Access Framework
- MediaStore
- WorkManager

## 本地构建

需要 JDK 17 和 Android SDK Platform 36：

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Debug APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

连接 Android 设备后安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 当前限制

- iCloud 中国区网页上的登录、隐私声明确认、双重认证、文件选择和下载必须由用户手动完成。
- Apple 没有向 Android 第三方 App 提供访问用户整个 iCloud Drive 的公开原生 API，因此云盘列表来自官方网页而不是原生接口。
- 下载文件默认保存在 `Download/iCloud Drive/`；照片只有在用户主动执行“导入”后才会额外写入系统相册。
- 本地下载目录不会镜像 iCloud 云盘的文件夹层级，同名文件会自动追加序号。
- App 不会在后台检测 iCloud 云端新增、修改或删除的文件。
- Live Photo 在 Android 相册中按图片和视频两个资源保存。
- 首个版本不执行 HEIC、H.265 或 RAW 转码。
- 尚未完成真实 iCloud 千文件批次和多厂商设备验证。

## 隐私

App 需要 `INTERNET` 权限加载中国区官方云盘网页和下载用户主动选择的文件。登录表单由 `www.icloud.com.cn` 提供；App 不注入 JavaScript、不读取密码或验证码。WebView 会在本机保存登录 Cookie，用户可在设置中随时清除。文件不会经过开发者服务器。
