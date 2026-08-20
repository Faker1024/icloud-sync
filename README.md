# iCloud 照片下载助手

一款面向 Android 的 iCloud 照片网页下载与本地导入助手。

用户在 Apple 官方 `icloud.com/photos` 页面完成登录、照片选择和下载；App 负责将下载得到的 ZIP、照片或视频安全地导入 Android 系统相册，并提供解压、去重、进度和导入历史能力。

## 项目状态

当前处于 MVP 设计阶段，Android 工程尚未初始化。

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
