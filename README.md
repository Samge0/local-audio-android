# kcourse-audio-android

「音频播放器」的 Android 客户端，与桌面 Web 版（kcourse-audio）功能对等：
把任意音频按目录结构放入音频目录即可播放。

## 功能

- **播放列表**：按目录分组、组内自然排序（第02讲 < 第10讲）、实时搜索、分组折叠（状态记忆）
- **进度记忆**：每首的上次播放位置 + 上次播放曲目，重开自动恢复
- **倍速播放**：0.1x ~ 4.0x（预设弹层 + ±0.1 微调，两位精度如 1.75x），全局记忆
- **播放模式**：顺序 / 列表循环 / 单曲循环 / 随机
- **字幕**：同名 `.srt` / `.vtt` / `.ass` / `.ssa` 自动挂载（支持语言后缀模糊匹配），
  以系统悬浮窗显示跟词字幕，点击悬浮窗折叠/展开，可拖动；开关记忆
- **定时停止**：预设（5/10/15/30/60 分钟）+ 自定义分钟（1~720）
- **黑胶唱片**：播放时旋转动画；列表滚动后顶部吸顶迷你控制条
- **其他**：±15s 快进退、上一首/下一首、媒体通知栏控制、拔耳机自动暂停、深色/浅色自适应
- **zip 导入**：粘贴下载链接（如 GitHub Release 直链）或选择本机 zip 文件，
  自动下载解压（保留目录结构、跳过 `__MACOSX`/隐藏文件、同名同大小去重、字幕文件一并导入、临时文件自动清理）
- **自定义音频目录 + 自动迁移**：系统目录浏览器选择或手动输入，换目录时自动迁移旧音频
  （同名同大小跳过、同名不同大小加 " (n)" 后缀共存）；支持一键清空目录（二次确认）

## 技术栈

- Kotlin + Jetpack Compose (Material3)
- Media3 ExoPlayer + MediaSessionService（前台服务，后台播放）
- WindowManager 系统悬浮窗（字幕）
- Gradle 8.13 / AGP 8.7.3 / Kotlin 2.0.21 / compileSdk 35 / minSdk 26

## 构建

```bash
# 1) 在项目根创建 local.properties，指向你的 Android SDK 路径
sdk.dir=<你的 Android SDK 路径>

# 2) 构建
./gradlew assembleDebug

# 产物: app/build/outputs/apk/debug/app-debug.apk
```

Android Studio 打开亦可（标准 Gradle 项目）。

## 权限说明

| 权限 | 用途 |
|---|---|
| 互联网 | zip 链接下载 |
| 前台服务 + 媒体播放 | 后台播放 |
| 唤醒锁定 | 播放时不休眠 |
| 悬浮窗（可选） | 字幕悬浮显示，可在系统设置授予 |
| 所有文件访问（可选） | 使用公共目录作为音频目录时需要 |

默认音频目录为应用专属存储（`Android/data/.../files/audio`），无需任何存储权限。

## 音频目录说明

- 默认目录：应用专属外部存储（无需任何权限）
- 自定义目录（如 `/storage/emulated/0/Music/xxx`）：Android 11+ 需授予「所有文件访问」权限（应用内会提示）
- zip 导入的目标 = 当前音频目录（可在导入框里指定子目录）

## 项目结构

```
app/src/main/java/com/samge/kcourseaudio/
  App.kt / MainActivity.kt       entry + SAF pickers + temp cleanup
  player/
    PlayerService.kt             MediaSession foreground playback service
    SubtitleOverlayService.kt    floating subtitle window (fold/drag)
  data/
    SettingsStore.kt             audio dir / rate / mode / last track / sub / collapse
    Library.kt                   scan + grouped natural sort + subtitle match
    PosStore.kt                  per-track position memory
    Subtitle.kt                  SRT/VTT/ASS parser + cue lookup
    SubtitleBridge.kt            VM ↔ overlay service state bridge
    ZipImporter.kt               zip download + extract (safe path)
    DirMigrator.kt               auto-migrate on dir change
  ui/
    MainViewModel.kt             playback / import / migrate / subtitle logic
    PlayerApp.kt                 all Compose UI (vinyl, list, popups, mini-bar)
    Theme.kt                     light/dark color scheme
  util/
    Fmt.kt                       time/size format + natural sort
    TreeUri.kt                   SAF tree uri -> real path
```
