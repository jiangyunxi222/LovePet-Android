# LovePet Android

适用于 Android 11 及以上版本，使用系统提供的 Android System WebView。

[下载 LovePet 1.0.0 正式 APK](https://github.com/jiangyunxi222/BandoriLovePet/releases/tag/LovePet_Android_V1.0.0)

- 手机角色包协议 v1 支持 ZIP、LPK 和 WPK，三种格式共用应用内的角色工作台。Windows 制作工具 `BandoriLovePetRoleBuilder` 中的 `PROTOCOL.md` 定义 `bandorilovepet-role` 的文件布局、命名和校验规则。应用继续兼容旧版 BandoriLove 单角色 ZIP 和第三方 LPK、WPK。创意工坊 LPK 缺少配置时，可授权其所在目录以读取 `config.json`。
- 普通 Cubism 3 及以上模型首次导入时会联网获取并校验 Live2D 官方运行库。经典 Cubism 2 模型需要从含运行库的 BandoriLove 角色包初始化。完整角色包带动作、表情、衣装、语音和字幕。
- 点「显示桌宠」并授予浮窗权限，即可拖动角色、双指缩放。完整角色包用腿部触控区打开菜单，第三方 LPK、WPK 支持可识别的包内触控。「关闭菜单」只收起菜单。可在控制台或桌宠菜单退出桌宠。
- 在应用内「设置」可选择「浮于应用上层」或「在主屏幕显示」。后者需要系统的使用情况访问权限，打开其他应用时隐藏桌宠，回到主屏幕后恢复。
- 「设置 → 权限配置」可查看悬浮窗、使用情况访问和动态壁纸的状态，返回应用后自动更新。动态壁纸通过系统确认。如果系统确认后仍显示旧壁纸，可从这里打开应用信息，检查系统是否提供「动态壁纸服务」权限。
- 双击角色预览的空白背景，在角色控制和全屏纯享之间切换。
- 「触控菜单」在角色发行包中默认开启。第三方模型默认关闭，开启后点右下角「⋯」即可打开。
- 「设置 → 主题配色」提供 12 套主题，可调整软件的按钮、卡片和设置界面。
- 「互动收藏」将角色触控触发的动作、表情、换装和语音限制在收藏内容内。
- 桌宠浮窗按当前姿态的角色轮廓和可见控件裁切。旋转和缩放期间保留空间，最后松手约半秒后收紧。向上或向下贴边可隐藏约 80% 的模型高度，并留出可拖回的区域。三指旋转后可接续双指缩放和单指拖动。双指或三指双击均可复位，关闭桌宠再开启也会恢复默认位置、大小和角度。
- 在「设置 → 壁纸设置」组合背景、Live2D 和特效。「添加图片」和「添加视频」分别打开系统相册的图片及视频选择器。支持 PNG、JPG、WebP 和静音循环的 MP4，可调整图层顺序、显示、位置、大小和透明度。
- 默认使用原来角色预览的浅粉背景，固定保留在最底层，不能隐藏、删除或移走。添加图片或视频即可覆盖。删除自己的背景后，自动露出默认背景。
- 点「＋ 特效」选择 12 种特效：星光、流星、星座、落樱、落雪、细雨、萤火、蝴蝶、泡泡、彩纸、柔光、音符。每行 4 个、共 3 行，可多层叠加。「素材特效」「图层排序」「图层调整」可分别收起。
- 特效由 [tsParticles](https://github.com/tsparticles/tsparticles) 3.9.1 Slim 驱动，使用普通 Canvas 以兼容更多 Android WebView，随 APK 离线提供，限制为 30 FPS，画面不可见时暂停。
- 编辑画面中，选中图层后可单指拖动、双指缩放，松手自动保存；也可用滑杆微调。默认打底背景固定，不参与拖动。
- 壁纸编辑器底部的「应用壁纸」会保存场景并直接发起系统应用确认；当前已使用本应用壁纸时直接更新场景。权限配置根据系统返回的授权状态显示「已开启」或「未开启」。所有机型优先尝试直接预览，仅在系统限制更换壁纸或预览确实未打开时回退到动态壁纸列表。预览旁的设置入口会尝试打开系统权限管理，找不到专用入口时会提示，并提供「系统壁纸」和「应用设置」。应用成功提示等待应用内模型首帧就绪后显示，同一次应用只提示一次。点「退出壁纸」后再选择要恢复的壁纸，可从相册选图，或使用系统或已保存的壁纸。
- 壁纸显示在桌面图标下方，并使用桌面发送的空白处点击触发互动。装饰与特效图层穿透触控，素材和设置保存在本机。
- 「更换模型」可从本机导入记录中切换模型，也可导入或删除。编辑器的「壁纸配置」可以保存带预览图的图层组合，之后继续使用。删除单条记录需要确认，其他配置引用的素材会保留。

## 构建调试 APK

准备 JDK 17 或更新版本、Android SDK Platform 35、Build Tools 35.0.0。脚本使用 Android SDK 工具直接编译、对齐和签名。

```powershell
pwsh -File ./build.ps1 -Sdk 'D:\Android\Sdk' -Jdk 'D:\Java\jdk-17'
adb install -r dist/BandoriLovePet-debug.apk
```

本地签名密钥位于 `build/debug/debug.keystore`，用于后续覆盖安装同一个调试包。角色素材和 Live2D 专有运行库均由用户导入。

### 正式 APK

用正式密钥和签名轮换证明构建，口令通过环境变量 `BANDORILOVEPET_ANDROID_KEYSTORE_PASS` 注入。正式包关闭应用与 WebView 调试。

```powershell
pwsh -File ./build.ps1 -ReleaseKeystore 'D:\signing\release.p12' -SigningLineage 'D:\signing\lineage.bin'
```

正式包输出到 `dist/BandoriLovePet.apk`。LovePet 的显示版本为 1.0.0，Android 内部版本号为 12。应用 ID 为 `io.bandorilove.lovepet`，签名轮换证明用于已有安装的覆盖升级。

tsParticles 使用 MIT 许可证，完整声明位于 `vendor/tsparticles-LICENSE.txt`，依赖来源与校验值位于 `vendor/tsparticles-sources.json`，均随 APK 一并打包。其他依赖见 [第三方声明](THIRD_PARTY_NOTICES.txt)。
