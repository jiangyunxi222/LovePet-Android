# BandoriLovePet for Android

适用于 Android 11 及以上版本，使用系统提供的 Android System WebView。调试包由独立 Android 分支构建。

- ZIP 仅支持 BandoriLove 单角色发行包。第三方模型可选择 LPK 或 WPK。创意工坊 LPK 缺少配置时，可授权其所在目录以读取 `config.json`。
- 普通 Cubism 3 及以上模型首次导入时会联网获取并校验 Live2D 官方运行库。经典 Cubism 2 模型需要从含运行库的 BandoriLove 角色包初始化。完整角色包带动作、表情、衣装、语音和字幕。
- 点「显示桌宠」，授予系统的浮窗权限。可拖动角色、双指缩放。完整角色包用腿部触控区打开菜单，普通模型点角色可短暂显示胸前的「⋯」。也可从控制台或系统通知退出桌宠。
- Android 通过系统浮窗显示桌宠。Windows 的 Live2DViewerEX 连接和桌面层级由 Windows 版提供。

## 构建调试 APK

准备 JDK 17 或更新版本、Android SDK Platform 35、Build Tools 35.0.0。脚本使用 Android SDK 工具直接编译、对齐和签名。

```powershell
pwsh -File android/build.ps1 -Sdk 'D:\Android\Sdk' -Jdk 'D:\Java\jdk-17'
adb install -r android/dist/BandoriLovePet.apk
```

本地签名密钥位于 `android/build/debug.keystore`，用于后续覆盖安装同一个调试包。角色素材和 Live2D 专有运行库均由用户导入。
