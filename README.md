# iCloud 中国区云盘客户端

一款面向 Android 和中国大陆 Apple 账户的第三方 iCloud Drive 浏览与下载工具。

登录、双重认证、文件夹浏览和下载均使用 App 自己编写的 Jetpack Compose 界面，不嵌入或打开 iCloud 网页。底层直接连接中国区 iCloud Web 服务：密码通过 SRP 在设备上生成登录证明，登录后使用加密保存的会话访问 Drive/Document 接口。

> Apple 没有提供访问用户整个 iCloud Drive 的公开 Android API。本项目使用未公开的 Web 接口，Apple 调整接口后可能需要升级；本项目与 Apple Inc. 无关联或授权关系。

## 当前能力

- 原生 Apple 账户与密码登录界面，仅连接 `idmsa.apple.com.cn`。
- SRP-6a 登录：密码不明文发送、不写入磁盘。
- 原生双重认证界面，支持受信任设备推送和短信验证码。
- 支持高级数据保护账户的 PCS 授权，在受信任设备批准后取得云盘解密授权。
- 浏览整个 iCloud Drive 的文件夹和任意类型文件。
- 图片文件显示真实缩略图，点击可在 App 内全屏预览。
- 文件管理器支持列表/网格切换，缩略图与文件图标可在 48–144 dp 之间调整。
- 支持按名称、修改时间、文件大小或文件类型升序/降序排列，文件夹始终优先；布局、尺寸和排序设置都会持久保存。
- 全局采用接近 iOS Files 的视觉语言：系统蓝主色、分组灰背景、白色圆角浮层、矢量文件图标、胶囊状态和半透明风格导航；完整支持深色模式与系统字体缩放。
- 单文件下载继续交给 Android `DownloadManager`；长按文件夹可递归同步全部文件并保留目录层级。
- 文件夹同步由 WorkManager 在后台执行，默认以 3 路受限并发下载；每个文件独立进行原子写入、字节数校验、修改时间保留和即时重试，任务失败后使用指数退避重试；重新同步可校正已有文件时间而不重复下载内容。
- “本地”标签页直接浏览 `Download/iCloud Drive/` 中已经同步的目录和文件；图片显示本地缩略图并可在 App 内全屏预览，其他格式可安全交给已安装的查看器打开。
- 本地文件支持当前目录搜索、列表/网格切换，以及按名称、修改时间、总大小或文件类型升降序排列；设置会独立持久化，大目录排序在后台线程完成。
- 云端与本地图片查看器支持超大图分块解码、双指缩放、惯性拖动、双击放大、比例显示和一键复位；图片使用全屏沉浸式画布，工具栏自动隐藏并可单击唤出；可左右滑动浏览当前目录中的其他图片，本地文件还能使用其他 App 打开或分享。
- 下载地址强制 HTTPS 并执行 Apple/iCloud 主机白名单校验。
- 原始文件保存到公共目录 `Download/iCloud Drive/`，不把非媒体文件写入图库。
- 会话令牌和 Cookie 使用 Android Keystore AES-GCM 加密保存，可随时退出并清除。
- 可选通过系统文件选择器把照片、视频或 ZIP 导入 `DCIM/iCloud Photos/`。
- ZIP 安全检查、SHA-256 去重、MediaStore 原子写入、Room 历史和 WorkManager 后台导入。

## 数据与保存位置

| 数据 | 保存方式 |
| --- | --- |
| Apple 账户密码 | 仅在登录期间驻留内存，不保存 |
| 双重认证验证码 | 仅用于本次验证，不保存 |
| 会话令牌和 Cookie | Android Keystore AES-GCM 加密后保存在 App 私有空间 |
| 云盘原始文件 | `Download/iCloud Drive/`；文件夹同步保留其云端层级 |
| 已同步文件索引 | 直接读取 Android MediaStore，不复制文件、不额外保存路径数据库 |
| 图片预览缓存 | App 私有缓存；退出 iCloud 登录时清除 |
| 用户主动导入的照片/视频 | `DCIM/iCloud Photos/` |
| 文件与账户数据 | 全程设备直连 iCloud，不经过开发者服务器 |

选择 Downloads 作为云盘默认位置，是因为 iCloud Drive 可能包含 PDF、Office 文档、压缩包和工程文件；照片导入系统相册是独立、可选的后续操作。

## 技术栈

- Kotlin、Jetpack Compose、Material 3（iOS 风格自定义主题与 Rounded Icons）
- MVVM、Coroutines、StateFlow
- OkHttp、`org.json`
- Android Keystore、SRP-6a（RFC 5054 2048-bit group / SHA-256）
- Android DownloadManager、Storage Access Framework、MediaStore、WorkManager
- Room、DataStore、WorkManager、Hilt
- `minSdk 29`、`compileSdk/targetSdk 36`

## 本地构建

需要 JDK 17 和 Android SDK Platform 36：

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Debug APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

连接 Android 设备后安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 已知限制

- 私有接口没有兼容性承诺，不能保证 Apple 调整后仍可用。
- 高级数据保护账户必须开启“允许访问 iCloud 数据”，并在每次 Apple 要求时使用受信任设备批准临时访问。
- 首次登录、双重认证和真实文件下载必须使用测试专用中国大陆 Apple 账户在真机验证；仓库与自动化测试不包含任何账户凭据。
- 文件夹同步必须由用户长按主动发起；不执行定时增量检测、双向同步、云端删除或上传。
- 文件夹同步会镜像包含文件的目录层级，但 Android MediaStore 无法单独发布完全空的目录。
- Apple 下载接口没有为所有文件提供可验证内容哈希；App 以响应 `Content-Length`（缺失时使用云端大小）验证是否完整写入。
- 如果账户尚未同意最新版 iCloud 中国区网页条款，需先在 Apple 官方入口完成同意。

## 文档与来源

架构、协议流程、安全设计、错误模型和验收标准见 [开发设计文档](docs/DEVELOPMENT.md)。

SRP 和 iCloud Drive 协议实现参考了 MIT 许可的 [rclone iCloud Drive backend](https://github.com/rclone/rclone/tree/master/backend/iclouddrive)，归属说明见 [NOTICE](NOTICE)。
