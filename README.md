# 词桨 JianYi

一款极简的离线中英学习 Android 应用。白底、无广告、无需注册，装上就能用。

> 当前版本：v3.16.1（versionCode 66）· 仅支持 arm64-v8a

## 下载安装

**[⬇ 下载最新版 APK](https://github.com/sparkl525/JianYi/releases/latest)**

要求 Android 7.0（API 24）及以上 / arm64-v8a。手机允许「安装未知来源应用」后即可安装；
装过旧版可直接覆盖安装，签名一致，不会丢学习记录。各版本变更见 [CHANGELOG.md](CHANGELOG.md)。

## 功能

| 功能 | 说明 |
|---|---|
| 📖 离线查词 | 内置 ECDICT 精选 35.5 万词条，完全离线 |
| 🌐 句段翻译 | ML Kit 离线翻译引擎；可选填入百度翻译 API 做云端增强 |
| 📷 拍照翻译 | 系统相机 + ML Kit 离线 OCR，支持框选局部识别 |
| 🎧 听力 | 内置 5 篇 VOA 慢速英语 + LRC 字幕跟读 |
| 📚 阅读 | 内置 5 篇 NASA 科普短文，点词查义 + 划段翻译 |
| ✏️ 记背 | 选择题 / 拼写题交替出题，六级词表按词频从高频到低频推进，带进度面板 |
| 🎮 配对消除 | 无尽模式小游戏：点英文与中文配对消除，六种随机牌阵、连击计分，整局进度存档 |
| 🔖 单词本 | 收藏词单独成册，底部 FAB 随时调出 |
| ❗ 错题集 | 背词答错、游戏配错的词自动归集，答对自动移出 |

- 记背 / 阅读 / 单词本 / 错题集为沉浸式页面，通过拖拽把手或滚到边界继续拖动退出；
  退出方向 = 进入方向的反向（"哪来的回哪去"）。
- 支持浅色 / 深色主题，护眼绿、米黄纸与自定义相册皮肤。

## 小游戏规则

- 屏幕上散布着牌面，点一块英文再点对应中文（顺序不限），配对成功即化成散沙；
  整屏 5 对全部消完才换下一批，牌阵每屏随机（错落 / 波浪 / 梅花桩 / 环形 / 散点 / 分列）。
- 配对得分 = `10 × 连击数`，连击封顶 ×5，即 +10 / +20 / +30 / +40 / +50。
- 配错 −10、连击清零，分数不低于 0；配错的两块词会记入回炉队列，三屏之后各再出现一次。
- 一屏 5 对全消额外 +20「清屏」。
- 出词顺序：六级高频 → 中频 → 低频 → 其他词，错题集与单词本中的词不受此限，
  按词级还有颜色标记（高频琥珀、六级重点砖红）。
- 分数不存档，退出重进从 0 开始；但**词序游标、未消完的牌面、回炉队列、历史最高分**都会保留。

## 技术要点

- **纯原生 Java**（无 AndroidX UI 依赖），`minSdk 24` / `targetSdk 34`
- 词典以 gzip 压缩的 SQLite（`.dbz`）随包分发，运行时解压只读
- 手势系统要点：
  - 沉浸页退出用「拖拽把手（自己消费 ACTION_DOWN）+ 边界滚动（onInterceptTouchEvent 与
    onTouchEvent 双保险）」两条路，避开 `setOnTouchListener` 收不到子 View 事件的坑
  - 横向翻页是手写的 `SwipeHost`（OverScroller + XY 判向拦截）
- 沉浸页显隐顶栏 / 标签栏时，内容区几何突变用等量反向 `translationY` 补偿，切页不跳
- 皮肤不是主题级的：`applySkin()` 逐 View 铺背景，深浅色则经 `ThemeHelper` 改写 uiMode 走 `values-night`
- 小游戏是自绘 View：单个 `postOnAnimation` 循环驱动全部动效，静止即停；
  牌阵生成用「随机图案 → 旋转 AABB 两两对推消重叠 → 仍有重叠则重掷」的松弛算法
- 学习数据落在 `study.db`：背词游标、收藏、错题、游戏存档分表存放

## 目录结构

```
app/src/main/java/com/jianyi/dict/   # 全部业务代码（16 个文件）
app/src/main/assets/                 # 词典 / 内置听力与阅读素材
tools/                               # 词典构建、素材加工、出题质检等辅助脚本（Python）
```

## 自己构建

需要 JDK 17、Android SDK（Platform 34、Build-Tools 34.0.0）、Gradle 8.7：

```bash
export JAVA_HOME=/path/to/jdk-17.0.2
export ANDROID_HOME=/path/to/android-sdk
gradle assembleRelease --offline
```

或直接用 Android Studio 打开工程。

## 数据来源

- 词典：[ECDICT](https://github.com/skywind3000/ECDICT)（MIT）
- 听力：VOA Learning English（美国政府作品·公共领域）
- 阅读：NASA 科普文章（美国政府作品·公共领域）
- 增量素材走 jsDelivr CDN 分发，本仓库不含全部素材包

## License

[MIT](LICENSE)
