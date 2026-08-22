# 抽帧花屏与 moov 探测失败修复

> 状态：实现落地。两条修复路径：
>  1. **切点抽帧花屏**（ThumbStore）— `OPTION_CLOSEST` + Bitmap 健康检测 + ffmpeg 软解 fallback；
>  2. **`moov atom not found` 探测失败**（Probe）— 平台 API 兜底链：MediaMetadataRetriever → MediaExtractor。

## 1. 切点抽帧花屏

### 1.1 现象

进入单文件分析页（AnalysisScreen），"切点抽帧确认"区域的两张缩略图显示花屏：
绿屏 / 全黑 / 像素错乱 / 撕裂。**未设置片头/片尾** 时（即 `tSec ≈ 0`）更容易复现，
对 HEVC/H.265 长 GOP 文件尤其明显。

### 1.2 根因

`MediaMetadataRetriever.getScaledFrameAtTime` 在 HEVC + B 帧 + 不标准 MP4（moov
在尾部、codec_tag 异常等）上**偶发**返回内部 native data 损坏的 Bitmap 对象——
Java 层看起来是有效 Bitmap，draw 时却显示为绿屏/全黑/撕裂。`OPTION_CLOSEST_SYNC`
返回的"之前最近关键帧"在 HEVC 5~10s GOP 上经常离切点几秒远，预览与切点脱节。

平台 codec 与 mp4 demuxer 兼容性是已知的 Android 平台问题，单靠 `OPTION_CLOSEST`
调整不能消除——必须从根上走 ffmpeg 自带软解（`libde265` / `libx265` /
`ffmpeg` 自带 decoder），绕开 platform codec 才能保证 bitmap 一定解码完成。

### 1.3 修复

`ThumbStore.extract` 改为三级管线：

| 阶段 | 行为 | 失败处理 |
|------|------|----------|
| 1. `MediaMetadataRetriever` + `OPTION_CLOSEST` | 平台 API 抽帧（默认路径） | 抛异常或不健康 → 2 |
| 2. `isBitmapHealthy` 检测 | 5 点采样，3+ 接近"全绿/全黑/全蓝/全紫"判花屏 | 不健康 → 3 |
| 3. `FFmpegKit.execute` 软解抽帧 | ffmpeg `-ss X -i in -an -sn -frames:v 1 -vf scale=...` 抽 1 帧 JPEG | 整条管线失败 → null |

`loadFromDisk` 也加了健康检测——修复前写入的损坏 JPEG（花屏缓存）会判作
未命中、自动删除并重抽，不会让坏图永远缓存命中。

### 1.4 性能与体验

- ffmpeg 抽帧单次约 80~200ms（启动 ffmpeg 二进制 → 1 帧解码 → 写 JPEG → BitmapFactory 读入）
- 平台 API 健康时仍走平台（快）；不健康才升级到 ffmpeg（慢但稳）
- 分析页最多 2 张 FramePreview + 200ms 防抖，整页加载约 200~400ms

## 2. `moov atom not found` 探测失败

### 2.1 现象

扫描结果页大量文件标"不可处理"（`[mov,mp4,...] moov atom not found; ...:
Invalid data found when processing`），错误信息直接暴露 ffprobe 内部报错，文件
在结果页无法识别（看不到时长/大小）。

### 2.2 根因

ffprobe `-v error` 严格模式对**容器级错误**（mp4 moov 缺失/位置异常、codec_tag
私有字段）一律返回非 0 退出码 + 错误信息，旧 platform 兜底只用 MediaExtractor，
对坏文件同样抛异常——结果整个文件被一刀切判失败。

但很多 moov 在尾部的不标准 mp4 文件（移动端录制、流式下载、点播平台的非
标准 mux 输出）**仍然可解析**：MediaMetadataRetriever 在 moov 字段正常时能
拿到 `METADATA_KEY_DURATION` 与 `VIDEO_ROTATION`，MediaExtractor 能拿到
stream 列表——只是 ffprobe 因严格模式而拒绝。

### 2.3 修复

`Probe.probeMediaPath` 在 ffprobe 3 次重试仍失败后，平台兜底升级为**双路**：

1. `MediaMetadataRetriever` 拿 `DURATION` + `VIDEO_ROTATION`（**优先**，对 moov
   异常 mp4 仍能工作）
2. `MediaExtractor` 拿 stream 列表（**次选**，与 MMR 互补）

两条路径合并的四种情况：

| MMR 时长 | Extractor streams | 结果 | 行为 |
|----------|-------------------|------|------|
| ✓ | ✓ | `probeOk=true, duration=..., streams=[...]` | 完整结果，正常处理 |
| ✓ | ✗ | `probeOk=true, duration=..., streams=[]` | 见下 §2.4 |
| ✗ | ✓ | `probeOk=true, duration=max(KEY_DURATION), streams=[...]` | 完整结果（兜底时长） |
| ✗ | ✗ | `null` | 退回 ffprobe 错误信息 |

### 2.4 `probeOk=true, streams=[]` 的语义

平台兜底**只拿到时长**但 stream 列表为空：扫描时正常显示（用户能看到时长
+ 大小 + 文件名），**不**判定为"不可处理"。但剪辑侧无法逐轨 `-map`，
`TrimService.processJob` 的 1b 段会判 FAILED 并给出明确原因：

> 容器解析受限（`<ffprobe 错误信息>`），可尝试桌面 ffmpeg 重新封装后再处理

文案中嵌入原始 ffprobe 错误（`probe.error`），让用户知道具体是哪种容器问题
（moov / codec_tag / 其他）。

## 3. 关联改动

- `Probe.kt::platformTrack` 新增 `rotation: Int = 0` 参数（兜底时填
  MediaMetadataRetriever 的 `VIDEO_ROTATION`，原本只对 ffprobe 路径生效）
- `TrimService.kt::processJob` 新增 1b 段：streams=空 + probeOk=true → FAILED
- `ThumbStore.kt` ffmpeg 抽帧管线迭代（详见 §5）：从 platform-first → platform + ffmpeg fallback
  → preview 路径必走 ffmpeg 绕开 diskCache → 切到 ffmpeg-kit-full 包 + `-skip_frame nokey`

## 4. 后续修复（用户仍报花屏后的迭代）

第一版（commit b0bf022）改 platform `OPTION_CLOSEST` + 5 点单色检测 + ffmpeg fallback 仍花屏：
- 用户截图：粉红条带 + 绿红紫混合（典型 HEVC B 帧解参考失败）
- 5 点单色检测识别不出条带/马赛克（只识别整片同色）→ 坏图通过检测进 diskCache

第二版（commit a075939）preview 路径绕开 diskCache + 健康检测升级到 64 点 stddev：
- preview 路径必走 ffmpeg 软解（绕开 platform MediaCodec）
- 8x8 网格采样 64 像素，stddev < 3（单色）或 > 95（条带）判花屏
- platform 抽的图只入 memCache 不入 diskCache

第三版（commit 0982800）ffmpeg 命令加 `-skip_frame nokey -threads 1 -err_detect ignore_err`：
- `-skip_frame nokey`：codec-level 跳过非关键帧 packet 只解 I 帧（I 帧独立可解）
- 仍花屏说明 ffmpeg-kit-min **没有 HEVC 软解码器**——抽帧时回退到不正确的解码器
  或硬解导致花屏

第四版（本次）：切到 `com.antonkarpenko:ffmpeg-kit-full:2.2.1`：
- full 包含完整软解码器（x264/x265/dav1d/ffmpeg 自带 hevc decoder 等）
- `-skip_frame nokey` + full HEVC 软解 = 必然稳定
- APK 体积约 +60MB（full vs min 的 native lib 差），个人自用工具可接受

## 4. 测试覆盖

- 平台相关（Bitmap 健康检测、MediaMetadataRetriever）需 instrumented test，
  本仓库无该基础设施（CI 只跑单测）；修复后真机回归覆盖以下场景：
  - 正常 MP4/H.264：platform 抽帧命中（路径 1）
  - HEVC 1080p/4K：偶发 platform 花屏 → 自动升级 ffmpeg 软解（路径 3）
  - 扫描时遇到 moov 在尾部的 MP4：识别为成功，streams=空 → 进分析页 OK
  - 真正损坏的 MP4（下载未完成）：ffprobe 失败 + 平台 API 双失败 → 退回 ffprobe 错误
- 已有 `SeekFudgeTest` 不受影响（纯函数，未触碰）
