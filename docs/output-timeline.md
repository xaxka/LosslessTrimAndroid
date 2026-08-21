# 输出时间轴三缺陷：起点非 0 / 结尾超播 / 片头剪丢音频

> 状态（2026-08-21 晚）：**加固批次落地**（§8：disposition/附件/章节/
> muxdelay/rotation-DV 警告/输出校验管线 + E2E 扩至 T5–T9），新增**永久
> 回归守卫** `.github/workflows/timeline-regression.yml`（main push + 所有
> PR 触发；单测断言命令形态、`scripts/verify-timeline.sh` 断言真实 ffmpeg
> 行为）——时间轴修复被回退会直接红。
> 状态（2026-08-21）：**修复已落地（main 1d4478c）并经 CI 验证通过**——
> Verify timeline #1：单测 + E2E 矩阵 T1–T4 全 PASS（ubuntu-latest，
> ffmpeg 6.1.1-3ubuntu5）；Android CI #66 同提交全绿。临时验证 workflow
> 已删除，单测 TrimCommandTest 并入 Android CI 常规流程。
> 前置问题（MKV+B帧 seek 前移一个 GOP）见 [mkv-bframe-seek-offset.md]。

## 1. 现象

用户报告成片两处时间轴异常，复现确认三项缺陷（样例均为 bf=8、GOP=2s、
60.1s、AAC + srt 的 MKV）：

1. **结尾超播**："时间轴结束还在不停播放"——30s 成片显示 40.08s 时长，
   画面到 30.16s 已结束，其后 ~10s 播放器继续黑屏"播放"（T1 实测）。
2. **片头剪丢音频**：从 0 开始剪（片头剪）的成片开头 ~0.72s 无声且时长
   同步缩水——音频超前视频的片源（真实片源常态，实测样例超前 0.8s）上
   `-ss 0` 会整段丢弃视频首关键帧之前的音频包（T3 实测）。
3. **起点非 0 / 时间轴错位**：时间不从 0 开始。两个来源：
   a) 中段剪不归零（前一个修复已用 make_zero 解决）；b) **片头剪**传
   make_zero 反而与 matroska 封装器位移打架，首包 pts 写成 −0.023s，
   播放器时间轴从负值/异常锚点起跳。

## 2. 根因

### 2.1 结尾超播：末尾长字幕 cue 撑大容器 Duration

字幕是**逐 cue 一个包**、duration 即 cue 时长（音频/视频包只有 ~20-40ms）。
跨过剪辑终点的长 cue（如 59.5→70.0s）整包保留：`-t` 只在**输入侧**拦新包，
已进管线的包原样写出，其 duration 原样落进容器。matroska 的 Duration 取
所有包 `max(pts+duration)` → 被字幕末端撑大 10s。播放器按容器 Duration
铺时间轴，视频流结束后自然"黑屏续播"。实测 T1：视频末端 30.16s，字幕末端
40.08s，容器 Duration=40.08s。

### 2.2 片头剪丢音频：`-ss 0` 的 seek 目标为负

沿用上一修复的门控（[mkv-bframe-seek-offset.md] §4）：含 B 帧的 matroska
上 `-ss 0` 的 seek 目标 = 0 + start_time(常为负，AAC priming) − 3/23s < 0，
落在视频索引首条目**之前**。matroska 定位失败后从 find_stream_info 的
预读位置续读并进入 `skip_to_keyframe`，把视频首个关键帧**之前**的音频包
整段丢弃。音视频起点齐平的片源只丢 priming 零头无感；音频超前视频的片源
（实测 itsoffset -0.8s 构造）开头 ~0.72s 静音（T3：音频包 1260 vs 完整
1292，丢 32 包）。

### 2.3 片头剪起点不归零：make_zero 与封装器位移打架

无 `-ss` 时 `avoid_negative_ts make_zero` 把首包 DTS 钉到 0，但输出侧
matroska 封装器还会再做一次自己的起始偏移，两者叠加把首包 pts 写成
−0.023s（实测）。ffmpeg 默认的 `avoid_negative_ts=auto` 则按需平移、
干净输出 start_time=0.000（T4 实测对比）。中段剪不受影响（seek 后首包
时间戳是源片中段的绝对值，必须显式 make_zero 归零，否则成片从 30:00
起跳——上一修复的既有行为保持不变）。

## 3. LosslessCut 的对策（调研结论）

[mifi/lossless-cut] 对应位置的策略，本次修复与之一致：

| 问题 | LosslessCut | 本应用 |
|------|-------------|--------|
| 片头剪是否发 `-ss` | `isCuttingStart`（cutFrom>0）才发；起点为 0 不发 | `seekArgs`：ss≤0.001 返回空 |
| avoid_negative_ts | `cuttingStart && ssBeforeInput` 才传 make_zero，否则用默认 | `avoidNegativeTsArgs`：片头剪省略（默认 auto），中段剪 make_zero |
| 结尾超播 | 保留所有流不钳时长（可选实验开关 `-shortest`，默认关） | setts bsf 只钳字幕包 duration（见 §5 为什么不用 -shortest） |

## 4. 修复（TrimService.buildCommand）

四个纯函数（单测 TrimCommandTest 覆盖）+ 装配逻辑：

```kotlin
// 1) 片头剪不发 -ss：从文件头顺序解复用，无 §2.2 的丢包路径
fun seekArgs(ss: Double): String =
    if (ss > 0.001) " -ss ${Formats.secs3(ss)} -noaccurate_seek" else ""

// 2) 片头剪不传 make_zero（用 ffmpeg 默认 auto）；中段剪保持 make_zero
fun avoidNegativeTsArgs(ss: Double): String =
    if (ss > 0.001) " -avoid_negative_ts make_zero" else ""

// 3) 字幕包时长钳制到剪辑区间内（修 §2.1 结尾超播）
fun subtitleClampBsf(durSec: Double): String =
    "setts=duration=if(gte(DURATION\\,0)\\,max(min(DURATION\\," +
        "(${Formats.secs3(durSec)}/TB)-TS)\\,0)\\,0)"

// 4) 门控：保留轨道含字幕流才追加 bsf
fun hasKeptSubtitle(probe: ProbeResult, kept: List<Int>): Boolean = ...
```

setts 表达式要点：TS/DURATION 是当前流时基刻度（非秒），TB 为时基，故
`-t` 值写作 `(T秒/TB)`；`\\,` 转义 bsf 序列分隔符里的逗号；最外层
`if(gte(DURATION,0),…,0)` 防护无 duration 的包（部分 PGS 轨）——NOPTS
表达式不可求值，不钳制时写 0。

ffmpeg-kit-min 2.2.2 内置 setts（`libavcodec.so` 导出，已用 strings 验证）。

## 5. 为什么不用 -shortest

`-shortest` 让输出停在**最短流**的末端：对结尾超播有效，但短音轨/短字幕
片源会把**视频硬截到音轨末端**——丢画面比"尾部黑屏"严重得多。LosslessCut
也只把它做成默认关闭的实验开关。钳字幕 duration 是无副作用的精准修复：
只影响本来就超出剪辑区间的尾巴，且 §1 实测音频/视频包 duration 短、
无需钳制。

## 6. 验证矩阵（T1–T4，全绿）

本地（沙箱 ffmpeg 6.1.1）与 CI（ubuntu-latest apt 同版本）均逐项 PASS，
关键数值一致：

样例 A `src.mkv`（音视频齐平 + AAC priming→start_time=−0.023 + 末尾
10.5s 长 cue 字幕）中段剪 30s；样例 B `lead.mkv`（音频超前视频 0.8s +
跨终点长 cue）片头剪 30s。`measure()` 输出容器 Duration、start_time、
视频/音频首包 pts、末包末端、音频包数、字幕末端、尾超（Duration−视频末端）：

| # | 命令 | 关键指标 | 结果 |
|---|------|---------|------|
| T1 | 旧·中段剪（-ss KF+fudge + make_zero） | 尾超 **9.92s**（dur=40.08，字幕端 40.08） | 复现确认 |
| T2 | 新·中段剪（+ `-bsf:s` 钳制） | 尾超 **0.04s**（dur=30.20，字幕端 30.157），st=0.011 | ✓ 修复 |
| T3 | 旧·片头剪（`-ss 0.000` + make_zero） | 音频包 1260（vs 完整 1292，**丢 0.72s**）+ 尾超 6.80s | 复现确认 |
| T4 | 新·片头剪（无 -ss/make_zero + 钳制） | 音频包 1292 **完整**、a0=0.000、st=0.000、尾超 0.28s | ✓ 修复 |

注：T4 视频首帧 v0=0.823s 是**源片本身形态**（音频超前 0.8s，auto 把最负
的音频包归零，视频自然后移）——无损剪辑保真，播放器从 0 起播时前 0.8s
有声无画，与源片行为一致，非缺陷。

## 7. 残余风险

- 无 duration 的字幕流（部分 PGS）不钳制（表达式防护写 0）：若其 cue
  跨终点仍可能小幅超播，量级通常 <1s。
- `-t` 与钳制目标一致（同为 durSec）：理论上 bsf 按 TB 换算的取整误差
  在 ~1 个时基刻度内，实测尾超 0.04~0.28s 均来自音频包尾部，可接受。
- 真机回归项（与上一修复合并跟踪）：aac 且 start_time<0 的真实片源、
  Cues 稀疏（非每 KF 一条）的片源。

## 8. 加固批次（轨道保真 + 输出校验管线）

在时间轴修复之上吸收的外部 spec 增量（其中该 spec 的"make_zero 恒定/
-ss 恒传"主张与本文件 §4 的实证结论相反，以本文件为准）：

### 8.1 命令模板新增（buildCommand）

| 参数 | 修的问题（实测复现号） | 门控 |
|------|----------------------|------|
| `-disposition:a:0 default`（其余音轨清 0） | 丢默认音轨后 copy 继承 disposition → 成片无 default 轨，依赖自动选轨的播放器"剪完没声音"（T5） | 保留音轨 ≥1 |
| `-map 0:t?` | MKV 附件（ASS 字幕字体/封面）整轨丢失 → 字幕排版/字体全毁（T6） | 仅 matroska 非 webm |
| `-map_chapters 0` | 章节保留并随切点自动平移（显式声明意图；实测 T6 首章平移到 0） | 恒定 |
| `-muxdelay 0 -muxpreload 0` | mpegts 默认 muxdelay 使成片 start_time=1.4s（T8 复现/修复对照） | 仅 TS/PS 系输出 |

### 8.2 输出校验管线（assessTimeline + runTrimVerified）

每个输出成功后跑 ffprobe（只读容器头）做四项断言：起点归零（\|st\|≤0.1s，
TS 系放宽 1.6s）、时长准确（±2s，同时覆盖字幕拖尾与 -t 锚定错误）、保留的
视频/音频流必须在输出里（防假成功空壳）。**失败且本轮用过字幕钳制 bsf 时
先去 bsf 降级重跑一次**——钳制是加固项不是必需项，exotic 字幕轨上 bsf
翻车不应让整个文件失败（最坏退回"结尾可能拖尾"的旧行为）。

### 8.3 探测与警告（非阻断，结果页琥珀色提示）

- **rotation**：解析 side_data Display Matrix（StreamInfo.rotation）。mp4→mp4
  无损保留；mp4→mkv 在 ffmpeg≥6.1 数据层面也保留（Projection 元素，T7
  实测），但部分播放器不识别 → 提示"可能横屏显示，建议 MP4 输出"。
- **Dolby Vision**：codec_name/codec_tag 命中 dvh1/dvhe/dva1/dvav。无损
  保留原样，非 DV 设备可能偏色/黑屏 → 提示（DV P5/P8 无损路径无解；
  P7 理论上可丢 EL 轨转纯 HDR10，片源罕见暂不做）。
- HDR10+ 的动态元数据在 SEI 里 copy 原样保留、不认识的老设备优雅降级为
  静态 HDR10，无需提示。

### 8.4 E2E 扩展（T5–T10，scripts/verify-timeline.sh）

| # | 场景 | 断言 |
|---|------|------|
| T5 | 双音轨源丢默认轨 | 旧命令 default=0 复现；新命令 default=1 |
| T6 | 附件 + 章节 | 附件流保留 ≥1；首章平移到 0、次章起点随切点平移 |
| T7 | 旋转元数据 | mp4→mkv / mp4→mp4 两侧 rotation 均保留 |
| T8 | TS 源 | TS→MKV 归零；TS→TS 无 muxdelay 复现 1.4s 偏移、加参数归零 |
| T9 | VFR 源（select 造非均匀网格） | 剪切归零、时长正确、解码零错误 |
| T10 | B帧 seek 落点（前置修复的回归锚） | 无 fudge 复现早落一个 GOP（vend>31.5）；修复臂 = T2 的 vend<30.7、\|dur−30\|<1s |

T5–T10 全部附带 `ffmpeg -v error` 解码扫描（残缺 GOP/时间戳坏档会现形）。

## 9. 回归守卫

`.github/workflows/timeline-regression.yml`（main push + 所有 PR +
手动触发）双防线：

1. **单测**（TrimCommandTest，22 用例）：纯函数断言 + **`assembleCommand`
   装配锚点**（逐字断言完整命令串——fudge 应用、-t 锚定、钳制、
   disposition、附件、faststart、降级路径任一步被改掉即红；E2E 脚本的
   命令是手写镜像，抓不住 app 代码的装配回退，装配锚点补上这个缺口）；
2. **E2E**（scripts/verify-timeline.sh，T1–T10）：真实 ffmpeg 验证命令
   形态的实际输出——错误参数即使编译通过也会现形（T1/T3/T5/T8/T10 各含
   "旧命令复现"对照，防止断言退化为恒真）。

[mkv-bframe-seek-offset.md]: ./mkv-bframe-seek-offset.md
[mifi/lossless-cut]: https://github.com/mifi/lossless-cut
