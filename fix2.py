import sys

f = r"D:\code\o\LosslessTrimAndroid\app\src\main\java\com\xixka\losslesstrim\util\ThumbStore.kt"
with open(f, "r", encoding="utf-8") as fh:
    content = fh.read()

old = '''        val vf = "scale='min($maxPx,iw)':-1,format=yuv420p"'''

new = '''        // 滤镜链：scale 缩放 → 显式 bt709 colorspace 转换 → format 降 10→8 bit
        // colorspace 滤镜解决 scale 输出 csp:gbr 与输入 bt709 不匹配问题
        // （10-bit HEVC 解码后 filter context csp 为 gbr，导致颜色错乱/花屏）
        val vf = "scale='min($maxPx,iw)':-1:out_color_matrix=bt709,format=yuv420p"'''

if old in content:
    content = content.replace(old, new)
    with open(f, "w", encoding="utf-8", newline='\r\n') as fh:
        fh.write(content)
    print("OK")
else:
    print("NOT FOUND")
    # Debug
    idx = content.find("val vf = ")
    if idx >= 0:
        print(repr(content[idx:idx+100]))
    sys.exit(1)
