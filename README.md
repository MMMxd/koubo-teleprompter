# 口播工坊（voice-over-machine）

口播创作者的一体化工具：**AI 写稿 → 提词器 → 热点借势 → 悬浮提词挂相机 → 语音跟随**，安卓原生（Kotlin，无 Compose）。

## 功能地图

| 模块 | 说明 |
| --- | --- |
| 稿件库 | 本地稿件管理，卡片直达全屏提词 |
| 热点榜 | 抖音/百度/头条/B站/微博 五源热榜聚合，热点标题一键进创作简报 |
| 写稿 | AI 三步创作（简报→方向→成稿，流式）+ 手动写；接 DeepSeek/OpenAI 兼容接口 |
| 全屏提词 | 匀速滚动 + 语音跟随（念到哪滚到哪、当前句琥珀高亮），速度/字号/透明度可调 |
| 悬浮提词 | 独立进程悬浮窗挂到相机上开拍；⚙ 面板：滚动/速/字/透/高/跟随，全部可调可记忆 |

## 构建

```powershell
# JDK 17 + Android SDK
# local.properties（不进仓库）需含：
#   sdk.dir=D\:\\tools\\android-sdk
#   llmKey=sk-你的DeepSeek密钥   （或启动后在 App「我的」页填）
.\gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（arm64-v8a + x86_64 双架构）。

## 技术要点

- sherpa-onnx 2.0.1（zipformer2 CTC 中文流式识别，模型打包于 assets/model，26MB）
- 识别引擎跑在 `:speech` 独立进程：native 层失败只死引擎进程，UI 永不闪退
- 麦克风采集在主进程前台服务（specialUse|microphone 类型），PCM 经广播喂给识别进程
- View 体系 + 自研控件（PillToggle 胶囊开关，规避 Android16 SwitchCompat 崩溃）

## 经验与踩坑

见 [docs/踩坑与经验-20261112.md](docs/踩坑与经验-20261112.md)——从本项目真实事故中沉淀，含复现、根因与修复。
