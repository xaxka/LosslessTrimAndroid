# AGENTS.md — AI 代理协作指南

## 项目简介

视频批量**无损**剪辑（ffmpeg stream copy，不转码）Android 应用，个人自用。支持头尾裁剪/区间保留两种模式、关键帧对齐切点、单文件分析视图，覆盖原文件或输出到 `CutVideos/`。

## 技术栈与结构

- Kotlin 1.9.24 + Jetpack Compose（Material 3，BOM 2024.06.00），AGP 8.5.2，JDK 17，minSdk 26 / compile & targetSdk 34，仅 arm64-v8a。
- ffmpeg：`com.antonkarpenko:ffmpeg-kit-min:2.2.1`（社区维护 fork，官方已归档）；剪辑走 `-c copy`，抽帧走软解（`out_range=pc` 修颜色范围）。
- Room 2.6.1（KSP）做探测/关键帧缓存，DataStore 记参数，前台 Service 串行队列。
- 单模块 `:app`，源码在 `app/src/main/java/com/xixka/losslesstrim/`，按功能分包：
  - `data/`：扫描（Scanner）、模型、设置（AppSettings）、Room 缓存（CacheDb）
  - `ffmpeg/`：Probe（ffprobe 封装）、SessionBridge
  - `trim/`：TrimPlanner（切点计算）、TrimController、TrimService（前台剪辑服务，含 buildCommand）
  - `ui/`：各 Compose 屏幕 + `theme/`（Blue Light UI 设计系统）
  - `util/`：CommandQuoting（命令转义）、ThumbStore（抽帧）、StorageAccess、MediaCodecThumb 等
- `app/src/test/`：JUnit 4 单测（命令形态、切点补偿、轨道匹配等）；`docs/`：排查记录；`scripts/`：E2E 验证脚本（仅 CI 使用）。

## 构建与 CI

CI 实际执行的命令（仅作参考，**本地禁止执行**）：

- `.github/workflows/android-build.yml`（Android CI，push main + 手动）：
  `./gradlew testDebugUnitTest --no-daemon --stacktrace` → `./gradlew assembleRelease --no-daemon --stacktrace -PversionName=… -PversionCode=…`；产物 artifact `LosslessTrimAndroid-release-apk` 并发布到 dev release。
- `.github/workflows/regression-guard.yml`（push/PR main）：
  `testDebugUnitTest` + `bash scripts/verify-thumb.sh` + `bash scripts/verify-timeline.sh`（真实 ffmpeg E2E 矩阵）。

版本号由 CI 以 `-PversionName/-PversionCode` 注入（Asia/Shanghai 日期规则，见 app/build.gradle.kts 注释），不要手写死。

## 硬性工作规则

- **禁止本地编译**：不要在本地运行任何构建/编译/测试命令（gradlew、npm 等一律不跑）；改动是否可用以 GitHub CI 编译通过为准。
- **小步提交**：每完成一个改动立即 commit 并 push，再进行下一项改动。
- **提交身份**：所有提交使用 xaxka 身份（本 clone 已配置 user.name=xaxka，user.email=73456104+xaxka@users.noreply.github.com，不要改动）。
- **任务收尾**：任务结束后清理本地 clone。

## 代码约定

- 全 Kotlin，注释/KDoc 一律中文，习惯写"背景/根因"式长注释（引用 ffmpeg 源码行为、提交 hash）。
- 日志用 `android.util.Log`，类内 `private const val TAG = "类名"`。
- ffmpeg 命令拼成单字符串，路径参数必须经 `CommandQuoting.quoteArg()` 转义（防拆词/注入），不要自行加引号。
- 输出文件遵守 `.part` 临时文件 → 校验 → 原子改名纪律。
- UI 遵循 Blue Light UI：固定浅色（冷白底 × 白卡 1dp 描边 × 浅蓝主强调 × 语义状态色），无阴影卡片，主题改动能看 `ui/theme/`。

## 关键文档/敏感区

- 改 ffmpeg 命令、切点/seek 补偿、时间轴逻辑前，先读 `docs/mkv-bframe-seek-offset.md` 和 `docs/output-timeline.md`；`TrimService.buildCommand` 的参数形态被 `TrimCommandTest` 逐字断言，regression-guard 会拦截回退。
- `ThumbStore` 抽帧链（软解优先、`out_range=pc` 颜色修复）同样在守卫覆盖内，勿轻易改动。
- SAF（`saf:`）数据通道已彻底移除（有 faststart 坏 MP4 与崩溃问题），不要重新引入；存储访问走"所有文件"权限 + 直路径单管线。
- `docs/` 曾随提交 1da92e2 被误删后恢复，删除/改名 docs 前先确认代码、单测、脚本无引用。
- `keystore.jks` 与签名密码在仓库内明文存放（README 已说明，个人自用），不要外传或改动签名配置。
