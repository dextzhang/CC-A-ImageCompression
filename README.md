# 超图压缩 (CC-A-ImageCompression)

超图压缩是一款专为 Android 平台打造的**高保真、极致元数据保留**的本地照片压缩工具。与普通的套壳 Web App 不同，本项目采用纯原生 Kotlin 进行开发，能够直接深度调用 Android 系统底层的 `MediaStore`、`ExifInterface` 以及高级存储权限，实现压缩后照片的精准时间归类与 GPS 定位防丢失。

---

## 📦 快速云端打包编译指南 (GitHub Actions)

为了降低开发门槛，项目已全面集成了 **GitHub Actions 云端自动构建**。您无需在本地配置繁重的 Android SDK 与 Java 环境，即可一键打包。

### 1. 触发构建
只要将代码推送（`git push`）到远程仓库的 `main` 或 `master` 分支，GitHub 就会自动激活构建流程：
```powershell
git add .
git commit -m "feat: 提交您的修改描述"
git push origin main
```

### 2. 获取 APK 产物
1. 访问您 GitHub 仓库的 **Actions** 标签页。
2. 点击最近一次运行的工作流（Workflow），例如：*Android CI Build*。
3. 滚动到详情页面的最底部，在 **Artifacts (产物)** 模块中，即可直接下载编译好的：
   * **`CC-ImageCompressor-Debug-APK`**：已自动签名的 Debug 测试包，可直接发送到手机覆盖安装。
   * **`CC-ImageCompressor-Release-APK`**：未签名的 Release 发行包。

---

## 💻 本地构建与运行调试指南

如果您需要在本地进行调试、运行或编译：

### 1. 环境准备
* **JDK 安装**：需安装 **JDK 17**（建议使用 Temurin 或 Oracle OpenJDK 17）。配置环境变量并确保在控制台运行 `java -version` 正常。
* **Android Studio**（可选，但推荐）：安装最新版的 Android Studio，它会全自动帮您下载并配置所需的 Android SDK（本项目需要 **SDK 34** 级别）。

### 2. 本地命令行编译
在项目根目录中，打开 PowerShell 或 Cmd 窗口：
* **编译测试版 (Debug APK)**：
  ```powershell
  # Windows 下执行本地 Gradle 编译
  .\gradlew.bat assembleDebug
  ```
  *编译成功后的 APK 路径*：`app/build.gradle` ➔ `app/build/outputs/apk/debug/app-debug.apk`

* **清理构建缓存**（当修改了资源或遇到怪异的编译报错时）：
  ```powershell
  .\gradlew.bat clean
  ```

### 3. 连接真机调试运行
1. 手机开启【开发者选项】并启用【USB 调试】。
2. 使用 Android Studio 打开本项目根目录。
3. 点击顶部工具栏的 **Run (绿色三角形图标)**，即可直接将应用安装并拉起到您的手机上进行实时断点调试。

---

## 🔢 如何修改版本号

当您发布新版本时，需要修改应用的版本号：
打开 **[app/build.gradle](file:///C:/Users/Administrator/Desktop/CC-A-ImageCompression/app/build.gradle)**，定位到 `defaultConfig` 闭包：
```groovy
defaultConfig {
    applicationId "com.cca.imagecompression"
    minSdk 26
    targetSdk 34
    versionCode 2         // 必须为递增的正整数，系统判定升级的唯一依据
    versionName "1.0.1"   // 展示给用户看的版本号字符串
    ...
}
```
修改完成后，再次推送代码或本地编译，新生成的 APK 就会自动应用新的版本号。
