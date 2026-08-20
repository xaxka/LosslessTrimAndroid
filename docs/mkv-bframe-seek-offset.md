# MKV(含B帧)无损剪辑：切点早落一个 GOP，字幕/成片时间整体偏移

> 状态（2026-08-20 追加）：**已排查"升级 ffmpeg 能否解决"——不能**。9.0.1 实测复现、
> master 源码确认启发修正原样存在、matroska 仍未设 `AVFMT_SEEK_TO_PTS`、kit 生态
> 也无可升级目标（详见 §6）。
> 状态（2026-08-20）：**根因已定位并在 stock ffmpeg 4.4.2 / 8.1.2 / 9.0.1 三版本复现验证**，
> 修复方案（-ss 补偿 3/23s）已用 8.1.2 命令行验证通过（T3），**代码改动尚未落地**。
> 明天从 §8 待办继续。

## 1. 现象

- 对含 B 帧的 MKV 剪辑后，成片**起点比应用对齐的关键帧早约一个 GOP**（样例 2.0s），
  成片时长也比请求值多出同样的量（`-t` 锚定在 `-ss` 值上，见 §4.4）。
- 表现为"字幕时间不对"：字幕与画面在成片内部**互相是同步的**（所有轨统一偏移），
  但成片头部多出约一个 GOP 的内容，字幕在成片时间轴上的位置比预期晚一个 GOP
  （样例：At32s 字幕应在 2.0s 出现，实际 4.08s）。
- 触发条件（已验证）：**matroska/webm 封装 + 视频流含 B 帧**。
  MP4/MOV 输入正常（`AVFMT_SEEK_TO_PTS`），无 B 帧的 MKV 也正常（T4）。

## 2. 一句话根因

ffmpeg 对没有 `AVFMT_SEEK_TO_PTS` 标志的封装（matroska 在内，mp4/mov 有）在任一流
含 B 帧时，会把 `-ss` 的 seek 目标**前移 3/23 秒（≈130.4ms）**做 DTS 启发修正
（`ffmpeg_opt.c`）；MKV 的 Cues 索引条目精确落在各关键帧 PTS 上，目标被前移后
向后搜索命中**前一个关键帧**，实际起点比对齐点早一个 GOP。

## 3. 复现证据（测试矩阵）

输入样例 `sub_in.mkv`：h264 `has_b_frames=2`、25fps、GOP=2.023s（KF 在
0/2.023/…/28.023/**30.023**/32.023…）、时长 60.023s、含 aac + subrip 内嵌字幕
（At5s/At32s/At38s/At52s）。Cues 共 31 条，视频索引条目精确在 KF 的 PTS
（…、28023→pos 6054456、**30023→pos 6489432**、32023→…）。

应用实际下发命令（`TrimService.buildCommand`）：
```
-ss <KF> -noaccurate_seek -i sub_in.mkv -t <dur> -map 0:0 -map 0:1 -map 0:2 \
  -c copy -map_metadata 0 -avoid_negative_ts make_zero -f matroska out.mkv
```

| # | ffmpeg | 输入 | 参数 | 结果 |
|---|--------|------|------|------|
| T1 | 4.4.2 和 8.1.2 | sub_in.mkv (B帧) | `-ss 30.023 -t 29.977`（应用原样） | 起点落 28.023 KF（早 1 个 GOP）；字幕 4.08/10.08/24.08；时长 32.08 |
| T2 | 8.1.2 | sub_in.mkv (B帧) | 同 T1 但去掉 `-noaccurate_seek` | **与 T1 完全一致** → 该旗标与 bug 无关 |
| T3 | 8.1.2 | sub_in.mkv (B帧) | `-ss 30.154 -t 29.846`（KF+0.131 补偿） | 起点精确落 30.023 KF；字幕 2.08/8.08/22.08 ✓；时长 30.08 |
| T4 | 8.1.2 | nb.mkv (`has_b_frames=0`) | `-ss 30.023 -t 29.977`（应用原样） | 起点精确落 30.023（输出 0.000 K）；时长精确 30.000 ✓ |
| T5 | **9.0.1** | sub_in.mkv (B帧) | `-ss 30.023 -t 29.977`（应用原样） | **与 T1 完全一致**（起点早 1 个 GOP；字幕 4.08/10.08/24.08；时长 32.08）→ 升级无效 |

环境：应用所用 kit `com.antonkarpenko:ffmpeg-kit-min:2.2.2` = **FFmpeg v8.1.1**
（Maven POM `<name>FFmpeg v8.1.1 Min</name>`）；沙箱用 4.4.2（系统）、
8.1.2 与 9.0.1（BtbN 静态构建）复现，行为一致 → **上游行为，非 fork 的锅**。

## 4. 根因链条（源码级）

### 4.1 DTS 启发修正（ffmpeg_opt.c open_input_file，8.1 同款逻辑在 demux 层）

```c
if (!(ic->iformat->flags & AVFMT_SEEK_TO_PTS)) {
    int dts_heuristic = 0;
    for (i = 0; i < ic->nb_streams; i++)
        if (ic->streams[i]->codecpar->video_delay) { dts_heuristic = 1; break; }
    if (dts_heuristic)
        seek_timestamp -= 3*AV_TIME_BASE / 23;   // = 130434.78µs ≈ 130.4ms
}
ret = avformat_seek_file(ic, -1, INT64_MIN, seek_timestamp, seek_timestamp, 0);
```

- matroska demuxer **没有** `AVFMT_SEEK_TO_PTS`（`mov.c:8278` 有 → mp4/mov 免疫）。
- 含 B 帧（`video_delay>0`）→ 目标 30.023 − 0.1304 = **29.8926**。

### 4.2 matroska_read_seek 按索引向后命中前一 KF

`matroskadec.c`：`av_index_search_timestamp(st, 29.8926, 0)` 取 **≤ 目标的最后一条**
索引 → 命中 28.023（30.023 的前一条）→ 落到前一个 GOP。之后
`skip_to_keyframe` 从该处第一个视频 KF（28.023）开始输出。

### 4.3 输出统一重整（内部同步，不是音画失步）

`ffmpeg.c`：`pkt->pts += ts_offset`（ts_offset = −ss 值）+ `-avoid_negative_ts
make_zero` → 所有流统一平移，**互相保持同步**；零点锚在首个 DTS（29.943，比
KF 早 2 帧/80ms）→ 全片内容再统一 +0.08s（均匀，无感）。真正的错误只有
"起点早一个 GOP + 时长多一个 GOP"。

### 4.4 `-t` 锚定在 `-ss` 值上

停止条件是输入时间轴 `pts ≥ ss值 + t值`（T1：30.023 + 29.977 = 60.0 → 直切到
片尾，输出 32.08s）。起点早落时时长同样多出一个 GOP；做补偿时 `-t` 也要同步减，
才能让终点仍精确落在 `actualEnd`。

## 5. 已排除的假设

- ~~`-noaccurate_seek` 引起~~：T1/T2 结果逐字节一致；该旗标只影响解码丢弃路径，
  `-c copy` 走 demuxer 的 skip_to_keyframe，与它无关（可考虑顺手删除该参数）。
- ~~ffmpeg-kit fork 的 bug~~：stock 4.4.2 / 8.1.2 均复现。
- ~~应用关键帧对齐算错~~：包扫描确认 30.023 确为 KF，`-ss` 传值正确，是 ffmpeg
  内部把目标前移。
- ~~`-avoid_negative_ts make_zero` 造成的 2s 偏移~~：只贡献 80ms 的统一零点平移。
- ~~输出封装问题~~：输入侧 seek 行为，与输出 mkv/mp4 无关。
- ~~升级 ffmpeg 版本能解决~~：见 §6，4.4.2 → 9.0.1 → master 全线同样行为。

## 6. 升级 ffmpeg 能否解决？——不能（2026-08-20 排查）

**结论：这是上游 ffmpeg 存续多年的既定行为，不是版本回归；升级 ffmpeg（连 master
都不行）不会改变，且 kit 生态当前也没有可升级的目标。修复只能走 §7 的应用侧补偿。**

### 6.1 版本矩阵实测/源码证据

| 版本 | 证据 | 结果 |
|------|------|------|
| 4.4.2（2021） | T1 实测 | 复现 |
| 8.1.1 | 应用所用 kit（antonkarpenko ffmpeg-kit-min 2.2.2，Maven POM） | 用户原始报告即此版本 |
| 8.1.2 | T1/T3/T4 实测 | 复现；补偿方案验证通过 |
| **9.0.1**（最新发布） | **T5 实测**（BtbN n9.0.1，2026-08-20 构建） | **复现，输出与 4.4.2 逐项一致** |
| master | 源码直查 GitHub | 启发修正原样存在（见 6.2） |

### 6.2 master 源码确认（两处都没动）

1. `fftools/ffmpeg_demux.c`（8.0 起 `ffmpeg_opt.c` 的 demux 逻辑拆到此文件），
   截至 2026-08-20 master 仍是一字不差的同款修正：

   ```c
   if (!(ic->iformat->flags & AVFMT_SEEK_TO_PTS)) {
       int dts_heuristic = 0;
       for (int i = 0; i < ic->nb_streams; i++) {
           const AVCodecParameters *par = ic->streams[i]->codecpar;
           if (par->video_delay) { dts_heuristic = 1; break; }
       }
       if (dts_heuristic) {
           seek_timestamp -= 3*AV_TIME_BASE / 23;
       }
   }
   ```

2. `libavformat/matroskadec.c` master 的 `ff_matroska_demuxer` 结构体
   **仍不设置 `AVFMT_SEEK_TO_PTS`**（全文件 0 处引用；mp4/mov 的 `mov.c` 有）。
   也就是说两处触发条件（无标志 + B帧启发修正）在最新开发版全部原样保留。

### 6.3 kit 生态现状（Android 可用的封装）

| kit | 最新版 | ffmpeg | 可升级性 |
|-----|--------|--------|----------|
| `com.antonkarpenko:ffmpeg-kit-min`（应用现用） | 2.2.2（2026-07-17，Maven metadata） | 8.1.1 | **无更新** |
| ffmpegkit-maintained/ffmpeg-kit | `v8.1.7-lts-android`（2026-07-03）等 | 8.1.7 / 7.1.x / 6.0.x | 最新仍是 **8.1 分支**，无 9.x 构建 |

即使将来出现 9.x/更新分支的 kit：由 6.1/6.2，行为也不会变。

### 6.4 对修复方案的推论（重要）

§7 的 `-ss + 0.131s` 补偿**面向未来安全**：若上游某天真移除了启发修正，seek 目标
（KF+0.131）向后搜索仍命中**同一个关键帧**（下一个 KF 在 +GOP ≥ 0.5s 处，远大于
0.131s），输出不变。唯一的理论边界是 GOP < 0.131s 的极端流（>7.6 KF/s 的监控类
视频），此时补偿可能跳到下一个 KF——与 §8.3 稀疏 Cues 同属残余风险项。

## 7. 修复方案（T3 已验证，待落地）

**做法**：对 matroska/webm 输入且视频含 B 帧时，`-ss` 加补偿量，`-t` 同步减。

- 常数：`SEEK_FUDGE_SEC = 0.131`（= 3/23 ≈ 0.130435 **向上取整到毫秒**）。
  ⚠️ 不能用 0.130：`-ss` 经 `Formats.secs3` 只保留 3 位小数，30.023+0.130=30.153
  → 内部减 130.435µs 后为 30022.565ms，按 ms 取整若落到 30022 < 30023 仍会命中
  前一个 KF；取 0.131 留出余量（实测 T3 即 0.131）。
- 公式：`ss = actualStart + 0.131`；`t = actualEnd − ss`。
- 门控：`ProbeResult.formatName` 含 `matroska`/`webm` **且** 视频流 `has_b_frames > 0`。
  无 B 帧不加（此时 ffmpeg 不做前移，加了反而可能跳到下一个索引条目）。
- mp4/mov 输入不加（无前移发生）。

改动点：

1. `data/Models.kt`：`StreamInfo` 增加 `hasBFrames: Int?`（null=未知，旧缓存）。
2. `ffmpeg/Probe.kt`：解析 `-show_streams` 的 `"has_b_frames"`（`optInt`，缺省 -1）。
   缓存兼容：`ProbeCacheEntity.streamsJson` 是 JSON 字符串，**无 Room schema 变更**，
   但旧缓存行会解析出 -1（未知）。
3. `trim/TrimService.kt`：`buildCommand` 需要拿到 entry（或预计算的 fudge 值），
   调整 `-ss`/`-t` 两处。

代码草稿（buildCommand 内）：

```kotlin
// ffmpeg 对无 AVFMT_SEEK_TO_PTS 的封装（mkv/webm/ts/avi…）在视频含 B 帧时会把
// seek 目标前移 3/23s（DTS 启发修正，ffmpeg_opt.c），导致向后搜索命中前一关键帧，
// 实际起点比对齐点早一个 GOP。这里把该量补回（向上取整到 ms 留余量）。
val matroskaIn = entry.probe.formatName.split(',').any {
    it.trim() == "matroska" || it.trim() == "webm"
}
val hasB = entry.probe.streams.firstOrNull { it.isVideo }?.hasBFrames ?: 1 // 未知按有B处理
val fudge = if (matroskaIn && hasB > 0) 0.131 else 0.0
val ss = plan.actualStart + fudge
val t = (plan.actualEnd - ss).coerceAtLeast(0.001)
```

## 8. 明天待办

1. 落地 §7 三处改动，真机回归：`sub_in.mkv` 样例剪辑后期望字幕在 2.08/8.08/22.08。
2. 决策：旧缓存 `has_b_frames` 未知（-1）时的策略——上面草稿按"视为有 B 帧"处理
   （matroska 下更安全：无 B 帧误加 fudge 只在 GOP < 131ms 才可能出错，极罕见；
   反向漏加则会稳定复现本 bug）。也可选择强制重探测。
3. 残余风险回归：Cues 粒度非"每 KF 一条"的真实片源（mkvmerge 长簇/稀疏 cues）——
   起点可能仍落在被命中簇的首个 KF；用真实 MKV 片源各测几段。
4. 章节时间：`-map_metadata 0` 复制的章节时间戳是否随 `-ss` 正确重整尚未结论
   （`fftest/chap_in.mkv`/`chap_out.mkv` 样例在，未深入）。
5. 可选清理：删除 `-noaccurate_seek`（对 `-c copy` 无作用）；
   TS/AVI 等同样无 `AVFMT_SEEK_TO_PTS` 的输入是否同样补偿（mpegts 无索引走通用
   seek，行为未测，先只对 matroska/webm 生效）。
6. 回归确认：MP4 输入路径行为不变；mkv→mp4 容器转换（输入侧 seek，补偿同样适用）。
7. ~~升级 ffmpeg / 换 kit 能否绕过~~：**已排查，不能**（§6，2026-08-20）。不做此项。

## 9. 测试资产与环境（沙箱，可能不保留）

- 样例与产物：`/data/user/work/fftest/`（`sub_in.mkv`、`nb.mkv` 无B帧对照组、
  `subs.srt`、`t1/t2/t3/t4b/t5.mkv`、`chap_in/chap_out.mkv`）。
- ffmpeg 8.1.2 静态构建：`/data/user/work/ff8/ffmpeg-n8.1-latest-linux64-gpl-8.1/bin/`。
- ffmpeg 9.0.1 静态构建：`/data/user/work/ff9/ffmpeg-n9.0-latest-linux64-gpl-9.0/bin/`。
- master 源码取证：`fftest/master_demux.c`、`fftest/master_matroskadec.c`
  （GitHub raw，2026-08-20 拉取）。
- 复现（T1）：`ffmpeg -hide_banner -y -ss 30.023 -noaccurate_seek -i sub_in.mkv -t 29.977 -map 0:0 -map 0:1 -map 0:2 -c copy -map_metadata 0 -avoid_negative_ts make_zero -f matroska t1.mkv`
- 验证字幕位置：`ffprobe -v error -select_streams s:0 -show_entries packet=pts_time -of csv=p=0 t1.mkv`
- 验证起点：`ffprobe -v error -select_streams v:0 -show_entries packet=pts_time,flags -of csv=p=0 t1.mkv | head`
- 关键帧清单：`ffprobe -v error -select_streams v:0 -show_entries packet=pts_time,flags -of csv=p=0 sub_in.mkv | grep K_`
