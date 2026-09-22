# 简译 JianYi

一款极简的离线中英学习 Android 应用。白底、无广告、无需注册，装上就能用。

> 最终发布版：v2.0.6（versionCode 29）· 仅支持 arm64-v8a

## 下载安装

**[⬇ 下载最新版 APK](https://github.com/sparkl525/JianYi/releases/latest)**

要求 Android 7.0（API 24）及以上 / arm64-v8a。手机允许「安装未知来源应用」后即可安装；
装过旧版可直接覆盖安装，签名一致，不会丢学习记录。各版本变更见 [Releases](https://github.com/sparkl525/JianYi/releases)。

## 功能

| 功能 | 说明 |
|---|---|
| 📖 离线查词 | 内置 ECDICT 精选 35.5 万词条，完全离线 |
| 🌐 句段翻译 | ML Kit 离线翻译引擎；可选填入百度翻译 API 做云端增强 |
| 📷 拍照翻译 | 系统相机 + ML Kit 离线 OCR，支持框选局部识别 |
| 🎧 听力 | 内置 VOA 慢速英语 + LRC 字幕跟读 |
| ✏️ 记背 | 选择题 / 拼写题交替出题，按遗忘曲线安排复习 |
| 📚 阅读 | 英文为主 + 点词查义 + 划段翻译（公版书源） |

- 记背 / 阅读为沉浸式页面，通过拖拽把手或滚到边界继续拖动退出；
  退出方向 = 进入方向的反向（"哪来的回哪去"）。
- 支持浅色 / 深色主题，护眼绿、米黄纸与自定义相册皮肤。

## 技术要点

- **纯原生 Java**（无 AndroidX UI 依赖），`minSdk 24` / `targetSdk 34`
- 词典以 gzip 压缩的 SQLite（`.dbz`）随包分发，运行时解压只读
- 手势系统要点：
  - 沉浸页退出用「拖拽把手（自己消费 ACTION_DOWN）+ 边界滚动（onInterceptTouchEvent 与
    onTouchEvent 双保险）」两条路，避开 `setOnTouchListener` 收不到子 View 事件的坑
  - 横向翻页是手写的 `SwipeHost`（OverScroller + XY 判向拦截）
- 沉浸页显隐顶栏 / 标签栏时，内容区几何突变用等量反向 `translationY` 补偿，切页不跳
- 皮肤不是主题级的：`applySkin()` 逐 View 铺背景，深浅色则经 `ThemeHelper` 改写 uiMode 走 `values-night`

## 目录结构

```
app/src/main/java/com/jianyi/dict/   # 全部业务代码（14 个文件）
app/src/main/assets/                 # 词典 / 内置听力与阅读素材
tools/                               # 词典构建、素材加工、出题质检等辅助脚本（Python）
```

## 自己构建

需要 JDK 17、Android SDK（Platform 34、Build-Tools 34.0.0）、Gradle 8.7：

```bash
export JAVA_HOME=/path/to/jdk-17.0.2
export ANDROID_HOME=/path/to/android-sdk
gradle assembleDebug
```

或直接用 Android Studio 打开工程。

## 数据来源

- 词典：[ECDICT](https://github.com/skywind3000/ECDICT)（MIT）
- 阅读 / 听力素材：古腾堡计划公版书、VOA 慢速英语（仅教育用途）
- 增量素材走 jsDelivr CDN 分发，本仓库不含全部素材包

## License

[MIT](LICENSE)
