# BandoriLove Pet for Android

适用于 Android 11 及以上版本。本地调试包由独立 Android 分支构建。

- 「选择模型」接受 BandoriLove 单角色 ZIP，以及 ViewerEX 的 LPK 和 WPK。已解压的模型请选角色目录，有多个入口时再选模型 JSON。创意工坊 LPK 可选同时含有 `config.json` 的目录。
- 首次请导入完整 BandoriLove 角色包来安装 Live2D 网页运行库。完整角色包带动作、表情、衣装、语音和字幕。普通 JSON、LPK 和 WPK 可预览并显示为桌宠。
- 点「显示桌宠」，授予系统的浮窗权限。可拖动角色、双指缩放，并从「⋯」菜单或控制台退出桌宠。通知权限允许从系统通知直接退出桌宠。
- Android 通过系统浮窗显示桌宠。Windows 的 Live2DViewerEX 连接和桌面层级由 Windows 版提供。

## 构建调试 APK

准备 JDK 17 或更新版本、Android SDK Platform 35、Build Tools 35.0.0。脚本使用 Android SDK 工具直接编译、对齐和签名。

```powershell
pwsh -File android/build.ps1 -Sdk 'D:\Android\Sdk' -Jdk 'D:\Java\jdk-17'
adb install -r android/dist/BandoriLove-Android-debug.apk
```

本地签名密钥位于 `android/build/debug.keystore`，用于后续覆盖安装同一个调试包。角色素材和 Live2D 专有运行库均由用户导入。
