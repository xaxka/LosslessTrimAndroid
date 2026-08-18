# LosslessTrimAndroid

视频批量**无损**剪辑（stream copy，不转码）Android 应用，个人自用。

## 功能

- 选一个文件夹（SAF），自动扫描视频（仅所选目录本身，不含子目录）
- 两种剪辑模式：
  - **头尾裁剪**：片头砍 X 秒、片尾砍 Y 秒（各文件按自身时长计算保留区间）
  - **区间保留**：所有文件统一保留 第A分B秒 → 第C分D秒
- 关键帧对齐（宁多切 / 宁少切 / 自动），无损剪辑切点只能落在关键帧上
- 单文件分析视图：时间轴 + 关键帧标记 + 切点抽帧预览 + 轨道逐轨勾选
- 输出容器：保持原容器 / MP4 / MKV（只换封装不转码）
- 批量串行处理（前台服务 + 通知栏进度 + 取消）
- 默认直接覆盖原文件（`.part` 临时文件成功后才替换），可选保留原文件输出到 `CutVideos/`
- 结果页：成功/失败/跳过统计、失败原因、一键重试、体积对比

## 技术栈

- Kotlin + Jetpack Compose（Material 3）
- UI 遵循 **Blue Light UI** 设计系统：固定浅色（冷白底 × 白卡 1dp 描边 × 浅蓝主强调 × 语义状态色），无阴影卡片、统一空状态、统计卡结果页
- [ffmpeg-kit 社区维护版 fork](https://github.com/sk3llo/ffmpeg-kit-flutter)（`com.antonkarpenko:ffmpeg-kit-min`，官方 ffmpeg-kit 已归档）
- SAF 存储访问（`saf:` 协议直接读写，无需 `MANAGE_EXTERNAL_STORAGE`）
- DataStore 参数记忆、前台 Service（dataSync）串行队列

## 构建

无需本地环境，推送到 GitHub 后 Actions 自动编译（`.github/workflows/android-build.yml`），
产物在 Actions Artifacts：`LosslessTrimAndroid-release-apk`。

本地构建：`./gradlew assembleRelease`（JDK 17，Android SDK 34）。

Release 签名使用仓库内 `keystore.jks`（个人自用工具，方便 CI 直接出可安装包）：
store/key 密码 `losstrim123`，alias `losstrim`。

## 使用注意

- 无损模式下切点只能落在关键帧上，实际切点与设定值最大偏差 0~1 个 GOP（通常 0.5~4s）
- 换容器为 MP4 时，srt 字幕 / DTS 等不兼容轨道会导致该文件失败（不会静默丢轨），改回 MKV 或原容器即可
- 覆盖模式会删除原文件，首次使用有确认弹窗；也可在设置中改为输出到 `CutVideos/` 子目录保留原文件
- minSdk 26，arm64-v8a
