## 口播工坊 v4.7 · 播放量杠杆工坊

本次更新把产品从「提词工具」升级为「播放量杠杆工具」：补齐短视频播放量公式里权重最高的三块——**钩子、标题封面、数据回流**。

### 🆕 新功能

- ⚡ **钩子工坊**：AI 按爆款钩子公式（数字冲击 / 反常识 / 悬念缺口 / 身份点名 / 损失厌恶 / 强对比 / 直给利益 / 场景奇观，8 类）重写视频前 3-5 秒第一句话，逐条打分并说明留存逻辑，一键替换稿件开头；稿件卡片实时显示「钩子强度」徽章
- 🎯 **标题封面工坊**：标题（≤20 字）+ 封面大字（6-12 字）+ 副文案（≤15 字）成对生成 5 套方案，按点击潜力排序，一键采用
- 📊 **发布台账**：发布后手动记一笔（平台 / 5秒完播 / 完播率 / 赞评转），长按可删；首页「创作驾驶舱」聚合 **5秒完播均值 · 钩子均分 · 本周发布场次**——只记账，永不自动发布
- 🎙 **语音速记**：车上/散步口述灵感，sherpa-onnx 离线识别（引擎内置）直接存成草稿；独立 `:speech` 进程，native 异常不影响主界面
- 🔥 **热点选题判断**：五大热搜每条可一键 AI 评判「值得做 / 可以做 / 不建议」并给切角建议
- 🏠 **创作驾驶舱**：首页新增紫色数据卡（本周稿件 / 发布场次 / 5秒完播均值 / 钩子均分）
- 🪝 稿件卡新增：钩子强度徽章 · 预计口播时长 · 发布场次

### 🛠 工程修复

- 钩子/标题工坊应用结果与编辑器双向同步（修复保存回滚问题）
- 语音速记挂 `:speech` 独立进程，sherpa native 崩溃零影响主界面
- 热点判断防连点重复请求

### 📦 安装

Android 6.0+，arm64 真机。APK 内置 26MB 离线语音模型，下载即用。

---

## Koubo Teleprompter v4.7 · Playbook Workshops

Upgrades the app from "teleprompter tool" to "views-lever tool" for spoken-video creators:

- ⚡ **Hook Workshop**: AI rewrites your first 3-5 seconds using 8 viral hook formula families, scores each variant, one-tap apply
- 🎯 **Title & Cover Workshop**: paired title (≤20 chars) + cover headline + sub-line generation, ranked by click potential
- 📊 **Publish Ledger**: manual per-post logging (platform, 5s retention, completion, likes/comments/shares) with home dashboard aggregates — bookkeeping only, never auto-publish
- 🎙 **Voice Memo**: speak ideas on the go → offline ASR (bundled engine) → saved as draft
- 🔥 **Topic Verdict**: one-tap AI judgment on every hot-search item (worth it / maybe / skip, with an angle suggestion)

Install: Android 6.0+, arm64. No account, no backend — data stays on device.
