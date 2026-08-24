#!/bin/bash
# E2E 回归守卫：输出时间轴 + 轨道保真。与 app TrimService.buildCommand 生成的
# 参数逐字对应。CI workflow（timeline-regression.yml）内嵌本脚本，防时间轴
# 修复被回退。
#   样例 A src.mkv（音视频起点对齐 + AAC priming→start_time=-0.023 + bf=8 +
#           末尾长 cue 字幕）——中段剪：
#     T1 旧命令：结尾超播复现（长字幕 cue 撑大 Duration，>5s）
#     T2 新命令：+字幕钳制 bsf → 超播消除、start_time≈0
#   样例 B lead.mkv（音频超前视频 0.8s——真实片源形态 + 跨终点字幕 cue）——片头剪：
#     T3 旧命令（-ss 0 + make_zero）：头部音频丢失复现（时长缩水）+ 超播
#     T4 新命令（不传 -ss 不传 make_zero + 钳制）：音频完整、start_time≈0、无超播
#   加固项（docs/output-timeline.md §加固）：
#     T5 disposition：丢默认音轨 → 输出必须重设 default（修"剪完没声音"）
#     T6 MKV 附件 + 章节：-map 0:t? 保留字体附件；章节随切点自动平移
#     T7 旋转元数据：mp4→mkv / mp4→mp4 转封装保留 Display Matrix
#     T8 TS 源：TS→MKV 归零；TS→TS 需 -muxdelay 0（默认 1.4s 偏移复现）
#     T9 VFR 源：非均匀时间戳网格剪切归零 + 解码干净
#     T10 B帧 seek 落点：-ss 无 fudge 复现早落一个 GOP（修复臂 = T2 落点断言）
#     T11 B帧源中段剪起点：make_zero 残留重排延迟复现（线上形态"输出校验
#         失败(起点未归零 start=0.200s)"）；修复=全程不传 -avoid_negative_ts
#         （ffmpeg 默认 auto），mkv/mp4 双容器归零
set -euo pipefail
W="$(mktemp -d)"
cd "$W"
echo "workdir=$W"
ffmpeg -version | head -1

# ---- 样例 A：bf=8 MKV + AAC(priming→start_time<0) + 末尾长 cue 字幕 ----
# （含一条 5s 的早期 cue：片头剪窗口内也有字幕包，供 measure() 量测）
cat > subs.srt <<'EOF'
1
00:00:05,000 --> 00:00:06,000
EarlyCue

2
00:00:32,000 --> 00:00:34,000
At32s

3
00:00:50,000 --> 00:00:59,000
NearEnd

4
00:00:59,500 --> 00:01:10,000
LastCueLongDur
EOF

ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=25:duration=60.1" \
  -f lavfi -i "sine=frequency=440:r=44100:duration=60.1" \
  -i subs.srt -map 0:v -map 1:a -map 2:s \
  -c:v libx264 -preset ultrafast -bf 8 -g 50 -keyint_min 50 -sc_threshold 0 \
  -c:a aac -c:s srt src.mkv

# ---- 样例 B：音频超前视频 0.8s（itsoffset），跨 30s 终点的长 cue 字幕 ----
cat > subs2.srt <<'EOF'
1
00:00:05,000 --> 00:00:06,000
EarlyCue

2
00:00:28,000 --> 00:00:36,000
StraddleEnd
EOF

ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=25:duration=60.1" \
  -itsoffset -0.8 -f lavfi -i "sine=frequency=440:r=44100:duration=60.1" \
  -i subs2.srt -map 0:v -map 1:a -map 2:s \
  -c:v libx264 -preset ultrafast -bf 8 -g 50 -keyint_min 50 -sc_threshold 0 \
  -c:a aac -c:s srt lead.mkv
LB_ST="$(ffprobe -v error -show_entries format=start_time -of csv=p=0 lead.mkv)"
echo "src.mkv start_time=$(ffprobe -v error -show_entries format=start_time -of csv=p=0 src.mkv) | lead.mkv start_time=$LB_ST"

measure() { # $1=file → "dur st v0 vend a0 aCount send over"
python3 - "$1" <<'PY'
import subprocess, sys
f = sys.argv[1]
def p(a): return subprocess.run(['ffprobe','-v','error']+a,capture_output=True,text=True).stdout.strip()
dur = float(p(['-show_entries','format=duration','-of','csv=p=0',f]))
st = float(p(['-show_entries','format=start_time','-of','csv=p=0',f]))
def pk(sel):
    return [l.split(',') for l in p(['-select_streams',sel,'-show_entries','packet=pts_time,duration_time','-of','csv=p=0',f]).splitlines() if l]
v, a, s = pk('v:0'), pk('a:0'), pk('s:0')
v0=float(v[0][0]) if v else -1.0
vend=float(v[-1][0])+float(v[-1][1]) if v else -1.0
a0=float(a[0][0]) if a else -1.0
send=float(s[-1][0])+float(s[-1][1]) if s else -1.0
print(f"{dur:.3f} {st:.3f} {v0:.3f} {vend:.3f} {a0:.3f} {len(a)} {send:.3f} {dur-vend:.3f}")
PY
}

# ---- 样例 A 切点：关键帧 30s + fudge（0.131 + max(0,-start_time)）----
ST="$(ffprobe -v error -show_entries format=start_time -of csv=p=0 src.mkv)"
KF="$(ffprobe -v error -select_streams v:0 -show_entries packet=pts_time,flags -of csv=p=0 src.mkv \
  | python3 -c "
import sys
for line in sys.stdin.read().splitlines():
    p = line.split(',')
    if len(p) >= 2 and 'K' in p[1] and 29.9 <= float(p[0]) <= 30.1:
        print(p[0]); break
")"
[ -n "$KF" ] || { echo "FATAL: KF not found"; exit 1; }
SS="$(python3 -c "
st = float('$ST') if '$ST' not in ('N/A','') else 0.0
print(f'{$KF + 0.131 + max(0.0,-st):.3f}')")"
T="$(python3 -c "
st = float('$ST') if '$ST' not in ('N/A','') else 0.0
print(f'{60.1 - $KF - 0.131 - max(0.0,-st):.3f}')")"
CLAMP="setts=duration=if(gte(DURATION\\,0)\\,max(min(DURATION\\,($T/TB)-TS)\\,0)\\,0)"
echo "sample A cut: ss=$SS t=$T"
echo "clamp: $CLAMP"

echo; echo "== T1 旧命令·中段剪（应复现结尾超播 >5s）:"
ffmpeg -hide_banner -loglevel error -y -ss "$SS" -noaccurate_seek -i src.mkv \
  -t "$T" -map 0:0 -map 0:1 -map 0:2 -c copy -map_metadata 0 \
  -avoid_negative_ts make_zero -f matroska t1.mkv
read -r DUR STT V0 VEND A0 ACNT SEND OVER <<< "$(measure t1.mkv)"
echo "  dur=$DUR st=$STT v0=$V0 vend=$VEND a0=$A0 aN=$ACNT sub_end=$SEND 尾超=$OVER"
python3 -c "exit(0 if $OVER > 5 else 1)" || { echo "T1 FAIL: 超播未复现"; exit 1; }
echo "  T1 PASS（复现确认）"

echo; echo "== T2 新命令·中段剪（+字幕钳制，auto 归零 → 超播 <0.5s、起点≈0）:"
ffmpeg -hide_banner -loglevel error -y -ss "$SS" -noaccurate_seek -i src.mkv \
  -t "$T" -map 0:0 -map 0:1 -map 0:2 -map "0:t?" -c copy -map_metadata 0 -map_chapters 0 \
  -bsf:s "$CLAMP" -disposition:a:0 default -f matroska t2.mkv
read -r DUR STT V0 VEND A0 ACNT SEND OVER <<< "$(measure t2.mkv)"
echo "  dur=$DUR st=$STT v0=$V0 vend=$VEND a0=$A0 aN=$ACNT sub_end=$SEND 尾超=$OVER"
# vend/dur 上界 = B帧 seek 精确落点断言（docs/mkv-bframe-seek-offset.md）：
# fudge 丢失时 seek 早落一个 GOP，视频多进 ~2s → vend≈32、dur≈32，此处即红
python3 -c "exit(0 if $OVER < 0.5 and $SEND < $DUR + 0.5 and abs($STT) < 0.5 and $VEND < 30.7 and abs($DUR - 30.0) < 1.0 else 1)" \
  || { echo "T2 FAIL"; exit 1; }
echo "  T2 PASS（超播消除、起点归零、精确落点）"

# ---- 样例 B：片头剪 30s ----
T4="30.000"
CLAMP2="setts=duration=if(gte(DURATION\\,0)\\,max(min(DURATION\\,($T4/TB)-TS)\\,0)\\,0)"

echo; echo "== T3 旧命令·片头剪（-ss 0.000 + make_zero，应复现头部音频丢失/时长缩水 + 超播）:"
ffmpeg -hide_banner -loglevel error -y -ss 0.000 -noaccurate_seek -i lead.mkv \
  -t "$T4" -map 0:0 -map 0:1 -map 0:2 -c copy -map_metadata 0 \
  -avoid_negative_ts make_zero -f matroska t3.mkv
read -r DUR3 STT3 V03 VEND3 A03 ACNT3 SEND3 OVER3 <<< "$(measure t3.mkv)"
echo "  dur=$DUR3 st=$STT3 v0=$V03 vend=$VEND3 a0=$A03 aN=$ACNT3 sub_end=$SEND3 尾超=$OVER3"

echo; echo "== T4 新命令·片头剪（不传 -ss/make_zero + 钳制 → 音频完整、起点≈0、无超播）:"
ffmpeg -hide_banner -loglevel error -y -i lead.mkv \
  -t "$T4" -map 0:0 -map 0:1 -map 0:2 -map "0:t?" -c copy -map_metadata 0 -map_chapters 0 \
  -bsf:s "$CLAMP2" -disposition:a:0 default -f matroska t4.mkv
read -r DUR4 STT4 V04 VEND4 A04 ACNT4 SEND4 OVER4 <<< "$(measure t4.mkv)"
echo "  dur=$DUR4 st=$STT4 v0=$V04 vend=$VEND4 a0=$A04 aN=$ACNT4 sub_end=$SEND4 尾超=$OVER4"

python3 - "$ACNT3" "$V03" "$SEND3" "$DUR4" "$STT4" "$A04" "$ACNT4" "$OVER4" <<'PY'
import sys
n3, v03, s3, d4, st4, a04, n4, o4 = map(float, sys.argv[1:9])
ok = True
def chk(cond, msg):
    global ok
    if not cond:
        print(f"  T3/T4 FAIL: {msg}")
        ok = False
# T3 复现：旧命令丢头部音频（包数骤减 + 视频被拉前 ~0.74s）+ 字幕超播
chk(n3 <= n4 - 20, f"T3 音频丢失未复现 (aN t3={int(n3)} vs t4={int(n4)})")
chk(v03 < 0.3, f"T3 内容丢失未复现 (v0={v03}，应被拉前到 ≈0.08)")
chk(s3 > 34.0, f"T3 片头剪超播未复现 (sub_end={s3})")
# T4 修复：音频完整、起点归零、无超播
chk(d4 > 29.95, f"T4 时长异常 (dur={d4})")
chk(abs(st4) < 0.05, f"T4 start_time 未归零 (st={st4})")
chk(a04 < 0.05, f"T4 头部音频缺失 (a0={a04})")
chk(o4 < 0.5, f"T4 结尾超播 (尾超={o4})")
sys.exit(0 if ok else 1)
PY
echo "  T3 PASS（复现确认）/ T4 PASS（修复生效）"

# =====================================================================
# 加固项回归（T5–T9）：disposition / 附件+章节 / 旋转 / TS 源 / VFR 源
# =====================================================================

# 解码零错误扫描：stderr 非空即失败（截尾残缺 GOP / 时间戳坏档都会现形）
decode_clean() {
  local f="$1" err
  err="$(ffmpeg -v error -i "$f" -f null - 2>&1 || true)"
  if [ -n "$err" ]; then echo "  decode errors in $f:"; echo "$err" | head -3; return 1; fi
  return 0
}

# 流级 JSON 便捷读取：py_stream <file> <python-expr on s (list of stream dicts)>
py_stream() {
  ffprobe -v error -show_streams -of json "$1" | python3 -c "
import json, sys
streams = json.load(sys.stdin)['streams']
$2"
}

# ---- T5 disposition：丢默认音轨后输出必须重设 default ----
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=25:duration=10" \
  -f lavfi -i "sine=frequency=440:r=44100:duration=10" \
  -f lavfi -i "sine=frequency=880:r=44100:duration=10" \
  -map 0:v -map 1:a -map 2:a -c:v libx264 -preset ultrafast -g 50 \
  -c:a aac -shortest twoaud.mkv
SRC_A0_DEF="$(py_stream twoaud.mkv "print([s.get('disposition',{}).get('default') for s in streams if s['codec_type']=='audio'][0])")"
echo; echo "== T5 disposition（源第一音轨 default=$SRC_A0_DEF，丢它留第二轨）:"
# 旧命令（无 disposition 重设）：输出音轨 default=0 → 播放器可能不自动选音轨
ffmpeg -hide_banner -loglevel error -y -ss 2 -i twoaud.mkv -t 4 \
  -map 0:0 -map 0:2 -c copy -map_metadata 0 -map_chapters 0 \
  -avoid_negative_ts make_zero -f matroska t5_old.mkv
T5_OLD_DEF="$(py_stream t5_old.mkv "print([s.get('disposition',{}).get('default') for s in streams if s['codec_type']=='audio'][0])")"
echo "  旧命令输出音轨 default=$T5_OLD_DEF（复现：无默认轨）"
[ "$T5_OLD_DEF" = "0" ] || { echo "T5 FAIL: 旧命令复现失败"; exit 1; }
# 新命令（app 现行为）：-disposition:a:0 default + auto 时间戳
ffmpeg -hide_banner -loglevel error -y -ss 2 -i twoaud.mkv -t 4 \
  -map 0:0 -map 0:2 -c copy -map_metadata 0 -map_chapters 0 \
  -disposition:a:0 default -f matroska t5_new.mkv
T5_NEW_DEF="$(py_stream t5_new.mkv "print([s.get('disposition',{}).get('default') for s in streams if s['codec_type']=='audio'][0])")"
echo "  新命令输出音轨 default=$T5_NEW_DEF"
[ "$T5_NEW_DEF" = "1" ] || { echo "T5 FAIL: disposition 重设未生效"; exit 1; }
decode_clean t5_new.mkv || { echo "T5 FAIL: 解码错误"; exit 1; }
echo "  T5 PASS（默认轨重设）"

# ---- T6 MKV 附件 + 章节：-map 0:t? 保留字体；章节随切点平移 ----
printf "[CHAPTER]\nTIMEBASE=1/1000\nSTART=1000\nEND=4000\ntitle=ch1\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=4000\nEND=9000\ntitle=ch2\n" > chap.txt
echo "fakefontdata" > font.bin
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=25:duration=10" \
  -f lavfi -i "sine=frequency=440:r=44100:duration=10" \
  -map 0:v -map 1:a -c:v libx264 -preset ultrafast -g 50 -c:a aac -shortest base10.mp4
ffmpeg -hide_banner -loglevel error -y -f ffmetadata -i chap.txt -i base10.mp4 \
  -map 1:v -map 1:a -map_chapters 0 -c copy att.mkv
ffmpeg -hide_banner -loglevel error -y -i att.mkv -attach font.bin \
  -metadata:s:t:0 mimetype=application/x-truetype-font -c copy attach.mkv
echo; echo "== T6 附件+章节（MKV 输出，-ss 2 -t 4）:"
ffmpeg -hide_banner -loglevel error -y -ss 2 -i attach.mkv -t 4 \
  -map 0:0 -map 0:1 -map "0:t?" -c copy -map_metadata 0 -map_chapters 0 \
  -disposition:a:0 default -f matroska t6.mkv
T6_ATT="$(py_stream t6.mkv "print(sum(1 for s in streams if s['codec_type']=='attachment'))")"
echo "  输出附件流数=$T6_ATT"
[ "$T6_ATT" -ge 1 ] || { echo "T6 FAIL: 附件未保留"; exit 1; }
ffprobe -v error -show_chapters -of json t6.mkv | python3 -c "
import json, sys
chs = json.load(sys.stdin).get('chapters', [])
assert len(chs) == 2, f'章节数异常: {len(chs)}'
first = float(chs[0]['start_time'])
second = float(chs[1]['start_time'])
assert abs(first) < 0.1, f'首章未平移到 0: {first}'
assert abs(second - 2.0) < 0.5, f'次章起点未随切点平移: {second}'
print(f'  章节: {chs[0][\"start_time\"]}->{chs[0][\"end_time\"]} / {chs[1][\"start_time\"]}->{chs[1][\"end_time\"]}（已平移）')
" || { echo "T6 FAIL: 章节处理异常"; exit 1; }
decode_clean t6.mkv || { echo "T6 FAIL: 解码错误"; exit 1; }
echo "  T6 PASS（附件保留 + 章节平移）"

# ---- T7 旋转元数据：转封装不丢 Display Matrix ----
ffmpeg -hide_banner -loglevel error -y -display_rotation 90 -i base10.mp4 -c copy rot.mp4
rot_of() { ffprobe -v error -select_streams v:0 -show_entries stream_side_data=rotation -of csv=p=0 "$1" | tr -d ' '; }
echo; echo "== T7 旋转元数据（源 rotation=$(rot_of rot.mp4)）:"
ffmpeg -hide_banner -loglevel error -y -ss 2 -i rot.mp4 -t 4 \
  -map 0:0 -c copy -map_metadata 0 -map_chapters 0 \
  -f matroska t7.mkv
ffmpeg -hide_banner -loglevel error -y -ss 2 -i rot.mp4 -t 4 \
  -map 0:0 -c copy -map_metadata 0 -map_chapters 0 \
  -movflags +faststart -f mp4 t7.mp4
echo "  mkv rotation=$(rot_of t7.mkv) | mp4 rotation=$(rot_of t7.mp4)"
[ "$(rot_of t7.mkv)" = "90" ] || { echo "T7 FAIL: MKV 丢失旋转"; exit 1; }
[ "$(rot_of t7.mp4)" = "90" ] || { echo "T7 FAIL: MP4 丢失旋转"; exit 1; }
echo "  T7 PASS（两侧容器均保留）"

# ---- T8 TS 源：TS→MKV 归零；TS→TS 需 -muxdelay 0 ----
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=25:duration=10" \
  -f lavfi -i "sine=frequency=440:r=44100:duration=10" \
  -map 0:v -map 1:a -c:v libx264 -preset ultrafast -g 50 -c:a aac -shortest -f mpegts src.ts
echo; echo "== T8 TS 源（-ss 2 -t 4，bf=0 无 fudge）:"
ffmpeg -hide_banner -loglevel error -y -ss 2 -noaccurate_seek -i src.ts -t 4 \
  -map 0:0 -map 0:1 -c copy -map_metadata 0 -map_chapters 0 \
  -disposition:a:0 default -f matroska t8.mkv
read -r ST8 D8 _ < <(ffprobe -v error -show_entries format=start_time,duration -of csv=p=0 t8.mkv | tr ',' ' ')
echo "  TS→MKV: start=$ST8 dur=$D8"
python3 -c "exit(0 if abs($ST8) < 0.1 and abs($D8 - 4.0) < 2.0 else 1)" || { echo "T8 FAIL: TS→MKV 时间轴异常"; exit 1; }
decode_clean t8.mkv || { echo "T8 FAIL: 解码错误"; exit 1; }
# 复现：TS→TS 不加 muxdelay → start_time=1.4s（广播流固有前导，播放无感但校验须放宽/清除）
ffmpeg -hide_banner -loglevel error -y -ss 2 -noaccurate_seek -i src.ts -t 4 \
  -map 0:0 -map 0:1 -c copy -map_metadata 0 -map_chapters 0 \
  -avoid_negative_ts make_zero -f mpegts t8_old.ts
read -r ST8O _ < <(ffprobe -v error -show_entries format=start_time,duration -of csv=p=0 t8_old.ts | tr ',' ' ')
echo "  TS→TS 无 muxdelay: start=$ST8O（复现 1.4s 偏移）"
python3 -c "exit(0 if $ST8O > 1.0 else 1)" || { echo "T8 FAIL: muxdelay 偏移未复现"; exit 1; }
# 修复：-muxdelay 0 -muxpreload 0 → start_time≈0
ffmpeg -hide_banner -loglevel error -y -ss 2 -noaccurate_seek -i src.ts -t 4 \
  -map 0:0 -map 0:1 -c copy -map_metadata 0 -map_chapters 0 \
  -muxdelay 0 -muxpreload 0 -f mpegts t8.ts
read -r ST8N D8N _ < <(ffprobe -v error -show_entries format=start_time,duration -of csv=p=0 t8.ts | tr ',' ' ')
echo "  TS→TS 加 muxdelay 0: start=$ST8N dur=$D8N"
python3 -c "exit(0 if abs($ST8N) < 0.1 and abs($D8N - 4.0) < 2.0 else 1)" || { echo "T8 FAIL: TS→TS 时间轴异常"; exit 1; }
decode_clean t8.ts || { echo "T8 FAIL: 解码错误"; exit 1; }
echo "  T8 PASS（TS 双路输出归零）"

# ---- T9 VFR 源：50fps 每取 1/3 帧 → 0.06s 网格；g=100 → KF@0/6/12 ----
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=50:duration=18" \
  -vf "select='not(mod(n,3))'" -c:v libx264 -preset ultrafast -g 100 vfr.mkv
echo; echo "== T9 VFR 源（-ss 6 -t 6）:"
ffmpeg -hide_banner -loglevel error -y -ss 6 -noaccurate_seek -i vfr.mkv -t 6 \
  -map 0:0 -c copy -map_metadata 0 -map_chapters 0 \
  -f matroska t9.mkv
read -r ST9 D9 _ < <(ffprobe -v error -show_entries format=start_time,duration -of csv=p=0 t9.mkv | tr ',' ' ')
echo "  VFR 剪切: start=$ST9 dur=$D9"
python3 -c "exit(0 if abs($ST9) < 0.1 and abs($D9 - 6.0) < 1.0 else 1)" || { echo "T9 FAIL: VFR 时间轴异常"; exit 1; }
decode_clean t9.mkv || { echo "T9 FAIL: 解码错误"; exit 1; }
echo "  T9 PASS（VFR 归零、解码干净）"

# ---- T10 MKV+B帧 seek 精确落点（docs/mkv-bframe-seek-offset.md 永久回归）----
# -ss 传关键帧原值（无 fudge）：ffmpeg 把 seek 目标前移 3/23s + start_time（
# AAC priming 为负），Cues 向后搜索命中前一个关键帧 → 起点早落一个 GOP，
# 视频多进 ~2s（vend≈32）。修复臂即 T2（ss=KF+fudge，已含 vend<30.7 断言）。
echo; echo "== T10 B帧 seek 落点（-ss $KF 无 fudge，应复现早落一个 GOP）:"
ffmpeg -hide_banner -loglevel error -y -ss "$KF" -noaccurate_seek -i src.mkv \
  -t "$T" -map 0:0 -map 0:1 -map 0:2 -map "0:t?" -c copy -map_metadata 0 -map_chapters 0 \
  -bsf:s "$CLAMP" -disposition:a:0 default -f matroska t10.mkv
read -r DUR10 STT10 V010 VEND10 A010 ACNT10 SEND10 OVER10 <<< "$(measure t10.mkv)"
echo "  dur=$DUR10 vend=$VEND10（预期 ≈32：早落一个 GOP，视频多进 ~2s）"
python3 -c "exit(0 if $VEND10 > 31.5 and $DUR10 > 31.5 else 1)" \
  || { echo "T10 FAIL: 早落 GOP 未复现（ffmpeg 行为变化？请重估 fudge 公式）"; exit 1; }
decode_clean t10.mkv || { echo "T10 FAIL: 解码错误"; exit 1; }
echo "  T10 PASS（复现确认；修复臂见 T2 落点断言）"

# ---- T11 B帧源中段剪起点归零（线上"输出校验失败(起点未归零 start=0.200s)"）----
# 根因：-ss 作输入项时 CLI 已用 ts_offset=-ss 把包时间戳拉回 0 附近；中段剪
# 传 make_zero 会把最小 DTS 钉 0，首帧 PTS 残留 B 帧重排延迟（bf3@12.5fps
# 实测 start=0.160s；线上样例重排 0.2s → start=0.200s），超过 app 校验阈值
# 0.1s → 好成片被误判失败。修复=全程不传 -avoid_negative_ts（ffmpeg 默认
# auto）：mp4 用 edit list+负 CTS（与相机直录 B 帧 mp4 同构）、mkv 用首包
# 基线，start_time=0；音频超前源还顺带修复 make_zero 把容器时长撑大的问题。
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "testsrc2=size=320x180:rate=12.5:duration=20" \
  -c:v libx264 -preset veryfast -bf 3 -g 25 -keyint_min 25 -sc_threshold 0 bfv.mp4
echo; echo "== T11 B帧中段剪（-ss 10 -t 5，make_zero 应复现残余起点 ≈0.160s）:"
# 复现臂：make_zero → start_time 残留重排延迟
ffmpeg -hide_banner -loglevel error -y -ss 10 -noaccurate_seek -i bfv.mp4 -t 5 -map 0:v -c copy \
  -map_metadata 0 -map_chapters 0 -avoid_negative_ts make_zero -f matroska t11_old.mkv
read -r ST11O _ < <(ffprobe -v error -show_entries format=start_time -of csv=p=0 t11_old.mkv)
echo "  旧命令 mkv: start=$ST11O（复现残余起点）"
python3 -c "exit(0 if $ST11O > 0.1 else 1)" || { echo "T11 FAIL: make_zero 残余起点未复现（ffmpeg 行为变化？）"; exit 1; }
# 修复臂：auto → mkv/mp4 双容器 start_time≈0
ffmpeg -hide_banner -loglevel error -y -ss 10 -noaccurate_seek -i bfv.mp4 -t 5 -map 0:v -c copy \
  -map_metadata 0 -map_chapters 0 -f matroska t11.mkv
ffmpeg -hide_banner -loglevel error -y -ss 10 -noaccurate_seek -i bfv.mp4 -t 5 -map 0:v -c copy \
  -map_metadata 0 -map_chapters 0 -movflags +faststart+use_metadata_tags -f mp4 t11.mp4
read -r ST11 _ < <(ffprobe -v error -show_entries format=start_time -of csv=p=0 t11.mkv)
read -r ST11M _ < <(ffprobe -v error -show_entries format=start_time -of csv=p=0 t11.mp4)
echo "  新命令 mkv: start=$ST11 | mp4: start=$ST11M"
python3 -c "exit(0 if abs($ST11) < 0.05 and abs($ST11M) < 0.05 else 1)" \
  || { echo "T11 FAIL: 起点未归零"; exit 1; }
decode_clean t11.mkv || { echo "T11 FAIL: mkv 解码错误"; exit 1; }
decode_clean t11.mp4 || { echo "T11 FAIL: mp4 解码错误"; exit 1; }
echo "  T11 PASS（B帧重排源 mkv/mp4 双容器起点归零）"

echo; echo "ALL PASS"
