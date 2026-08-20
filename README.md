# iCloud 照片下载助手

一款面向 Android 的 iCloud 照片网页下载与本地导入助手。

用户在 Apple 官方 `icloud.com/photos` 页面完成登录、照片选择和下载；App 负责将下载得到的 ZIP、照片或视频安全地导入 Android 系统相册，并提供解压、去重、进度和导入历史能力。

## 项目状态

MVP 已完成首个可构建版本，当前实现包括：

- 通过系统 Custom Tab 打开 Apple 官方 iCloud Photos 网页。
- 通过 Storage Access Framework 选择 ZIP、照片或视频。
- 本地暂存、存储空间预检和可取消的后台导入。
- ZIP 路径安全检查、条目数量限制和流式解压。
- JPEG、HEIC/HEIF、PNG、GIF、常见 RAW、MOV、MP4 识别。
- SHA-256 精确去重和同名文件保护。
- 使用 MediaStore 原子写入 `DCIM/iCloud Photos/`。
- Room 导入历史、进度、失败状态和相册名称设置。
- Android 10～16 构建基线，无广泛照片或全部文件权限。

## 产品边界

- 不接入非公开 iCloud Photos API。
- 不读取或保存 Apple 账户密码、验证码和 Cookie。
- 不注入或自动操作 iCloud 网页。
- 不提供实时或后台 iCloud 同步。
- 媒体处理默认只在用户设备本地完成。

## 开发文档

完整的产品边界、技术架构、数据模型、导入流程、安全设计、测试方案和开发里程碑见 [开发设计文档](docs/DEVELOPMENT.md)。

## 计划技术栈

- Kotlin
- Jetpack Compose + Material 3
- Room + DataStore
- Kotlin Coroutines + Flow
- AndroidX Browser Custom Tabs
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

- iCloud 网页上的登录、照片选择和下载必须由用户手动完成。
- App 无法检测 iCloud 云端新增或删除的照片。
- Live Photo 在 Android 相册中按图片和视频两个资源保存。
- 首个版本不执行 HEIC、H.265 或 RAW 转码。
- 尚未完成真实 iCloud 千文件批次和多厂商设备验证。

## 隐私

原生 App 不声明 `INTERNET` 权限。Apple 登录完全发生在系统浏览器中，导入的照片、ZIP、哈希和元数据只在本机处理。App 不读取 Apple 密码、验证码或浏览器 Cookie。
