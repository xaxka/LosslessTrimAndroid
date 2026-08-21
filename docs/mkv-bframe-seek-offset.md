# MKV(含B帧)无损剪辑：切点早落一个 GOP，字幕/成片时间整体偏移

> 状态（2026-08-21 晚 追加）：**本修复已纳入永久回归守卫**（timeline-regression
> workflow，main push + 所有 PR 触发）三层锚定，防止被回退：
> ① 单测 `TrimCommandTest.assemble mid cut…` **逐字断言完整命令**——
> `ss = actualStart + fudge` 或 `-t` 同步减 fudge 任一步被改掉即红（装配
> 锚点，比只测 seekFudgeSec 纯公式强：删掉"应用 fudge"那行也会被抓住）；
> ② E2E T10 复现臂：`-ss` 传关键帧原值断言早落一个 GOP（vend>31.5）；
> ③ E2E T2 修复臂新增落点断言（vend<30.7、|dur−30|<1s）——此前 T2 只断言
> 超播/起点，fudge 丢失时视频多进 2s **不会**触发任何断言（已修）。
> 状态（2026-08-20 晚 追加）：**修复已落地（main aebdb65..5667ad7）并经 CI 验证通过**。
> CI 复现实验发现 §7 原公式的缺陷：ffmpeg 还会把 `ic->start_time` 加进 seek 目标，
> AAC priming 等导致 start_time<0 的文件（实测 −23ms）实际前移量达 153ms，仅补
> 0.131 不够、bug 仍复现。公式已升级 `fudge = 0.131 + max(0, −start_time)`
> （根因见 §4.5，方案见 §7，证据 T6–T9）。
> 状态（2026-08-20 追加）：**已排查"升级 ffmpeg 能否解决"——不能**。9.0.1 实测复现、
> master 源码确认启发修正原样存在、matroska 仍未设 `AVFMT_SEEK_TO_PTS`、kit 生态
> 也无可升级目标（详见 §6）。
> 状态（2026-08-20）：**根因已定位并在 stock ffmpeg 4.4.2 / 8.1.2 / 9.0.1 三版本复现验证**，
> 修复方案（-ss 补偿 3/23s）已用 8.1.2 命令行验证通过（T3）。

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
（`ffmpeg_opt.c`）；目标还会**加上 `ic->start_time`**（§4.5，AAC priming 等使其
为负时等效再前移）。MKV 的 Cues 索引条目精确落在各关键帧 PTS 上，目标被前移后
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
| T6 | 6.1.1（CI） | ci_aac.mkv（B帧，**start_time=−23ms**，KF@30.000） | `-ss 30.000`（应用原样） | 复现：起点落 28.000；字幕 4.08 |
| T7 | 6.1.1（沙箱） | ci_aac.mkv 同构样例 | `-ss 30.131`（KF+0.131，原公式） | **仍复现**：起点落 28.000——总前移 130.4+23=153.4ms 超出补偿，暴露 §4.5 |
| T8 | 6.1.1（CI） | ci_aac.mkv | `-ss 30.154`（KF+0.131+0.023，新公式） | ✓ 起点精确落 30.000；字幕 2.08；首包 KF |
| T9 | 6.1.1（CI） | ci_pcm.mkv（B帧，start_time=0） | `-ss 30.131`（KF+0.131） | ✓ 起点精确落 30.000；字幕 2.08（T3 结论在 st=0 样例上成立） |

环境：应用所用 kit `com.antonkarpenko:ffmpeg-kit-min:2.2.2` = **FFmpeg v8.1.1**
（Maven POM `<name>FFmpeg v8.1.1 Min</name>`）；沙箱用 4.4.2（系统）、
8.1.2 与 9.0.1（BtbN 静态构建）复现，行为一致 → **上游行为，非 fork 的锅**。
T6–T9 为 GitHub Actions ubuntu-latest（ffmpeg 6.1.1-3ubuntu5）实测：ci_aac/ci_pcm
与 sub_in.mkv 同构（h264 bf=2、GOP=2s、60.1s、内嵌 srt），差别仅在音频编码——
aac encoder priming 使 ci_aac 的 start_time=−23ms（音/字幕轨起始 pts 为负），
pcm 则为 0。**sub_in.mkv 虽也是 aac 但 start_time=0**（其生成方式未引入负偏移），
T3 因此恰好只暴露 3/23s 一个偏移源而侥幸通过。

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

### 4.5 第二个偏移源：`ic->start_time`（2026-08-20 CI 验证时发现）

启发修正之前，seek 目标还会先加上容器起始时间（n6.1 `ffmpeg_demux.c:1559`，
紧邻 §4.1 代码块之前；4.x 在 `ffmpeg_opt.c` 同款逻辑；未设置 `-seek_timestamp`
选项时恒生效）：

```c
if (!o->seek_timestamp && ic->start_time != AV_NOPTS_VALUE)
    timestamp += ic->start_time;
```

`ic->start_time` = 各流最早起始 pts（ffprobe `format.start_time`）。AAC 等
encoder 的 priming 样本会让音频/字幕轨起始 pts 为负（ci_aac.mkv 实测：音/字幕
−23ms、视频 0 → format.start_time=−0.023）→ 目标**再前移 23ms**，总前移
130.435 + 23 = **153.4ms**，超出 0.131 补偿，bug 复现（T7）。

实测边界（ffmpeg 6.1.1，与 ci_aac.mkv 同构样例，KF@30.000，st=−23ms）：

| -ss | 实际起点 |
|-----|---------|
| 30.131（=KF+0.131） | 28.000 —— 原公式补偿量不足 |
| 30.152 | 28.000 |
| 30.153 | 30.000 ✓ |

与模型吻合：seek 目标换算到流的 ms 时基时取整（实测行为与四舍五入一致），
即要求 `ss ≥ KF + 0.130435 − st − 0.0005`；st=−0.023 → 阈值 30.152935，
正落在实测 30.152/30.153 之间。st=0 时阈值 KF+0.129935（ci_pcm.mkv 上 0.131
补偿通过的依据，余量 ~1ms；仅用 0.130 则余量只剩 65µs）。原 T3（sub_in.mkv）
通过有侥幸：该样例 start_time=0（见 §3 环境注），恰好只暴露了 3/23s 一个偏移源。

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

§7 的补偿公式**面向未来安全**：若上游某天真移除了启发修正，seek 目标
（KF + 0.131 + max(0,−st)；st≤0 时恰为 KF+0.131）向后搜索仍命中**同一个关键帧**
（下一个 KF 在 +GOP ≥ 0.5s 处，远大于补偿量），输出不变。唯一的理论边界是
GOP 小于补偿量的极端流（>7.6 KF/s 的监控类视频），此时补偿可能跳到下一个
KF——与 §8.4 稀疏 Cues 同属残余风险项。

## 7. 修复方案（已落地：main aebdb65..5667ad7，CI 验证通过）

**做法**：对 matroska/webm 输入且视频含 B 帧时，`-ss` 加补偿量，`-t` 同步减。

- **公式（2026-08-20 升级：纳入 §4.5 的 start_time 偏移）**：
  `fudge = 0.131 + max(0, −start_time)`；`ss = actualStart + fudge`；
  `t = actualEnd − ss`（`start_time` = ffprobe `format.start_time`）。
- 常数 `SEEK_FUDGE_SEC = 0.131`：st=0 时的实测阈值是 KF+0.129935（MKV ms 时基
  换算取整所致，§4.5），0.131 之上留 ~1ms 余量（KF 本身 ms 对齐，secs3 无舍入
  损失）。仅用 0.130 时余量只剩 65µs，亚毫秒扰动即翻车，不可用。
- `start_time > 0` 不缩小补偿（多补无害：补偿量 < GOP 间距时落点不变，少补有
  风险）；`start_time` 未知（null，平台 MediaExtractor 兜底路径）按 0 处理。
- 门控：`formatName` 含 `matroska`/`webm` **且** 视频流 `has_b_frames > 0` 且
  起点在片中（>0.001s；片头没有"更早的关键帧"可错落，不加）。无 B 帧不加
  （ffmpeg 不做前移，加了反而可能跳到下一个索引条目）；无视频流不加（纯音频/
  仅封面轨无 video_delay）；mp4/mov 输入不加。
- `hasBFrames` 未知（null）按含 B 帧处理：matroska 下误加仅在 GOP<131ms 才可能
  出错（极罕见），漏加则稳定复现本 bug。

落地改动（均在 main）：

1. `data/Models.kt`：`StreamInfo.hasBFrames: Int?`、`ProbeResult.startTimeSec: Double?`。
2. `ffmpeg/Probe.kt`：解析 `has_b_frames` 与 `format.start_time`（缺字段→null）。
3. `data/CacheDb.kt`：streamsJson 增量兼容（字段缺失→null=未知，服务平台
   MediaExtractor 兜底路径）；probe_cache 加 `startTimeSec` 列，**Room v2→v3**
   （纯缓存库走 destructive migration，丢一次缓存重新探测，符合既有设计）。
4. `trim/TrimService.kt`：`seekFudgeSec(actualStart, probe)` 纯函数（单测友好），
   `buildCommand` 调用：

```kotlin
fun seekFudgeSec(actualStart: Double, probe: ProbeResult): Double {
    if (actualStart <= 0.001) return 0.0
    val matroskaIn = probe.formatName.split(',').any {
        val f = it.trim(); f == "matroska" || f == "webm"
    }
    if (!matroskaIn) return 0.0
    // 无视频流（纯音频/仅封面）：无 video_delay，ffmpeg 不做前移，不补偿
    val video = probe.streams.firstOrNull { it.isVideo } ?: return 0.0
    // hasBFrames 未知（null=旧缓存行/平台兜底）按含 B 帧处理：漏加会稳定复现
    // 本 bug，误加仅当 GOP<131ms 才可能出错（极罕见）
    val hasB = video.hasBFrames ?: 1
    if (hasB <= 0) return 0.0
    val st = probe.startTimeSec ?: 0.0
    return SEEK_FUDGE_SEC + (-st).coerceAtLeast(0.0)
}
```

5. 单测 `app/src/test/java/com/xixka/losslesstrim/SeekFudgeTest.kt`（13 例）覆盖
   全部门控分支：matroska/webm/逗号分隔格式名、st 负/正/null、hasB 0/null、
   片头、无视频流、仅封面轨、常数值；已并入 `android-build.yml`
   （`testDebugUnitTest`），每次 push 常规回归。
6. CI 端到端验证：临时 workflow（ffmpeg 6.1.1 生成 ci_aac/ci_pcm 双样例跑
   T6–T9，断言"对照组复现 + 新公式修复"）全绿后已删除（run 32393681459，
   日志留在 Actions 历史）；单测保留为长期资产。

## 8. 待办（2026-08-20 更新）

1. ~~落地 §7 改动~~：**已完成**——aebdb65（修复主体）→ 1dc2f72（补 VideoEntry
   import）→ f335fb8（公式升级 + 单测 + CI 验证）→ eb2d441（CI 装 ffmpeg）→
   5667ad7（清理临时 workflow、单测并入常规 CI）。dev Release 已带修复。
2. ~~旧缓存 `has_b_frames` 未知策略~~：**已决策**——按"含 B 帧"处理（§7 理由）；
   `start_time` 未知按 0 处理，缓存 24h TTL 自然刷新。
3. 真机回归：装 dev Release APK，`sub_in.mkv` 样例剪 30.023s 起的段，期望字幕
   2.08/8.08/22.08；重点补测 **aac 且 start_time<0 的真实片源**（§4.5 场景，
   沙箱/CI 样例已验证，真机 kit 为 8.1.1 尚未直接复测该分支）。
4. 残余风险回归：Cues 粒度非"每 KF 一条"的真实片源（mkvmerge 长簇/稀疏 cues）——
   起点可能仍落在被命中簇的首个 KF；用真实 MKV 片源各测几段。
5. 章节时间：`-map_metadata 0` 复制的章节时间戳是否随 `-ss` 正确重整尚未结论
   （`fftest/chap_in.mkv`/`chap_out.mkv` 样例在，未深入）。
6. 可选清理：删除 `-noaccurate_seek`（对 `-c copy` 无作用）；
   TS/AVI 等同样无 `AVFMT_SEEK_TO_PTS` 的输入是否同样补偿（mpegts 无索引走通用
   seek，行为未测，先只对 matroska/webm 生效）。
7. 回归确认：MP4 输入路径行为不变；mkv→mp4 容器转换（输入侧 seek，补偿同样适用）。
8. ~~升级 ffmpeg / 换 kit 能否绕过~~：**已排查，不能**（§6，2026-08-20）。不做此项。

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
- CI 端到端取证（2026-08-20，GitHub Actions ubuntu-latest，ffmpeg 6.1.1-3ubuntu5）：
  [run 32393681459](https://github.com/xaxka/LosslessTrimAndroid/actions/runs/32393681459)
  （临时 Verify workflow，验证后删除，日志留在 Actions 历史）。样例生成命令与
  T6–T9 断言逻辑见该 run 日志；边界扫描（沙箱同版本 ffmpeg，ci_aac 同构样例）：
  ss=30.131→起点 28 / ss=30.152→起点 28 / ss=30.153→起点 30，锁定 §4.5。
