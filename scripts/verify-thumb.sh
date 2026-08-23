#!/bin/bash
# 缩略图抽帧回归守卫：验证 ThumbStore.kt 抽帧命令对 10-bit HEVC 的正确性。
#
# 背景：电视剧常见 10-bit HEVC 4K（Main 10@L5 + BT.709 limited range）。
#   问题1：旧软解命令带 -skip_frame nokey，与 -ss input seek 组合时，seek 到
#          非关键帧位置会无帧输出（目标点之前的 I 帧被 seek 丢弃、目标点又是
#          P/B 帧被跳过 → Filtergraph EOF），抽帧必然失败。
#   问题2：旧实现硬解 mediacodec 优先，10-bit 输出元数据/颜色不可靠。
#   修复：软解优先、去掉 -skip_frame nokey。
#
# 本脚本用 testsrc2 生成与电视剧同参数的 10-bit HEVC 视频，逐字复刻
# ThumbStore.kt 的 swInputCmd（软解 input-seek 命令），防止修复被回退。
# CI workflow（thumb-regression.yml）内嵌本脚本。
set -euo pipefail
W="$(mktemp -d)"; cd "$W"
echo "workdir=$W"
ffmpeg -version | head -1

# libx265 是生成 10-bit HEVC 样例的前提（grep -c 读完输入，避免 pipefail 下 SIGPIPE）
LIBX265_N="$(ffmpeg -hide_banner -encoders 2>/dev/null | grep -c libx265 || true)"
[ "$LIBX265_N" -gt 0 ] || { echo "FATAL: libx265 encoder not available in this ffmpeg"; exit 1; }

# ---- 生成 10-bit HEVC BT.709 limited range 测试视频（模拟电视剧参数）----
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc2=size=1920x1080:rate=25:duration=6" \
  -c:v libx265 -preset ultrafast \
  -x265-params "range=limited:colorprim=bt709:transfer=bt709:colormatrix=bt709" \
  -pix_fmt yuv420p10le -tag:v hvc1 src.mp4

echo "== sample video:"
ffprobe -v error -select_streams v:0 \
  -show_entries stream=codec_name,profile,pix_fmt,width,height,color_range,color_space \
  -of default=noprint_wrappers=1 src.mp4

# keyint 默认 250（10s），6s 视频仅第 0 帧是关键帧 → 2s 处必为非关键帧
VF="scale='min(384,iw)':-1:in_color_matrix=auto:out_color_matrix=bt709:out_range=pc"

echo; echo "== T1 修复后软解（无 -skip_frame nokey）抽非关键帧 2.000s → 必须成功"
ffmpeg -hide_banner -loglevel error -err_detect ignore_err -threads 4 -ss 2.000 -i src.mp4 \
  -an -sn -frames:v 1 -vf "$VF" -q:v 3 -y thumb_sw.jpg
[ -s thumb_sw.jpg ] || { echo "T1 FAIL: 软解抽非关键帧失败（修复被回退？）"; exit 1; }
echo "  T1 PASS: 输出 $(stat -c%s thumb_sw.jpg) bytes"

echo; echo "== T2 回归锚点：旧软解（带 -skip_frame nokey）抽非关键帧 → 应失败"
if ffmpeg -hide_banner -loglevel error -err_detect ignore_err -skip_frame nokey -threads 4 \
     -ss 2.000 -i src.mp4 -an -sn -frames:v 1 -vf "$VF" -q:v 3 -y thumb_bug.jpg 2>/dev/null \
   && [ -s thumb_bug.jpg ]; then
  echo "  T2 WARN: 旧命令竟成功——若 ThumbStore 已同步去 skip_frame，此锚点可移除"
else
  echo "  T2 PASS: 复现无帧失败（回归锚点成立）"
fi

echo; echo "== T3 颜色健康（8x8 网格 stddev，防单色花屏）"
# 注：testsrc2 含黑白棋盘等高对比测试元素，正常画面 stddev 可 >100，
# 故只断言「非单色」（绿屏/黑屏整片同色 stddev<3），不做条带上限判断。
python3 - thumb_sw.jpg <<'PY'
import subprocess, sys, statistics
f = sys.argv[1]
info = subprocess.run(['ffprobe','-v','error','-select_streams','v:0',
    '-show_entries','stream=width,height','-of','csv=p=0',f],
    capture_output=True, text=True).stdout.strip()
w, h = map(int, info.split(','))
raw = subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-i',f,
    '-f','rawvideo','-pix_fmt','rgb24','-'], capture_output=True).stdout
n = 8
rs, gs, bs = [], [], []
for sy in range(n):
    y = int((sy + 0.5) * h / n)
    for sx in range(n):
        x = int((sx + 0.5) * w / n)
        off = (y * w + x) * 3
        rs.append(raw[off]); gs.append(raw[off+1]); bs.append(raw[off+2])
rstd = statistics.pstdev(rs); gstd = statistics.pstdev(gs); bstd = statistics.pstdev(bs)
print(f"  stddev R={rstd:.1f} G={gstd:.1f} B={bstd:.1f}")
if rstd < 3 and gstd < 3 and bstd < 3:
    print("  T3 FAIL: 单色花屏（绿屏/黑屏）"); sys.exit(1)
print("  T3 PASS: 非单色，抽帧内容有效")
PY

echo; echo "ALL PASS"
