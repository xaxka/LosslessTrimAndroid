package com.xixka.losslesstrim.util

/**
 * ffmpeg-kit 命令字符串的路径转义（修注入面 + 特殊文件名正确性）。
 *
 * 背景：本应用把 ffmpeg/ffprobe 命令拼成**单个字符串**，最终由
 * `FFmpegKitConfig.parseArguments(cmd)` 拆成 argv（FFmpegSession.create /
 * FFmpegKit.execute 内部都走它）。该解析器**不是 shell**，语义（已对照
 * ffmpeg-kit 2.2.1 源码逐行验证）：
 *
 *  - 引号外的空格分词；
 *  - `"` 与 `'` 互为"切换型"引号：开/关各自引号态，**引号字符本身不进参数**；
 *    但一种引号在另一种引号内部是普通字符（如 `'` 在 `"..."` 内原样保留）；
 *  - 反斜杠不转义删除——它只是普通字符；唯一作用是让**紧随其后的引号**
 *    不再切换引号态，而是原样进参数（`\` 与该引号都保留）；
 *  - 由此：`-i "a"b.mp4"` 这类裸拼接，路径里的 `"` 会提前闭合引号，后续
 *    文本裸奔——`x" -y /sdcard/xx.mp4` 即注入任意 ffmpeg 选项（含 -y 覆盖
 *    任意目标）；而合法文件名 `a"b.mp4` 则被拆坏，剪辑必失败。
 *
 * [quoteArg] 保证任意路径（含 `"`、`'`、`\`、空格及组合）恰成一个 argv：
 *
 *  - 简单路径（无 `"` 且不以 `\` 结尾）沿用双引号包裹，与历史命令形态一致
 *    （单测逐字断言不破）；`'` 与空格在双引号内本就是字面量，安全；
 *  - 复杂路径改用单引号包裹：`"` 在单引号内天然字面量；
 *    路径里的 `'` 用三明治 `'"'"'` 表达（关单引号→`"'"`→重开单引号，
 *    参数里恰得一个 `'`）；若 `'` 前一个路径字符是 `\`，解析器的反斜杠
 *    规则会让它原样进参数，直接放行即可；
 *    末尾的 `\` 会"吃掉"闭合引号，故先闭合、再在引号外补尾部 `\`
 *    （引号外 `\` 是普通字符，其后必是命令分隔空格，安全）。
 */
object CommandQuoting {

    /** 路径 → 命令字符串里的一个参数（含必要的引号包裹）。空路径返回 ""，由 ffmpeg 报错。 */
    fun quoteArg(path: String): String {
        if (path.isEmpty()) return "\"\""
        // 简单路径：双引号包裹（`'`/空格在双引号内是字面量；`\` 不紧贴闭合引号即可）
        if (!path.contains('"') && !path.endsWith("\\")) return "\"$path\""

        // 复杂路径：单引号方案。先把尾部反斜杠摘出来（它们会让闭合引号失效）。
        var body = path
        var tail = 0
        while (tail < body.length && body[body.length - 1 - tail] == '\\') tail++
        if (tail > 0) body = body.substring(0, body.length - tail)

        val sb = StringBuilder()
        if (body.isNotEmpty()) {
            sb.append('\'')
            for (i in body.indices) {
                val c = body[i]
                if (c == '\'' && !(i > 0 && body[i - 1] == '\\')) {
                    // 三明治：' 关单引号 + "'" 字面量 + ' 重开单引号
                    sb.append("'\"'\"'")
                } else {
                    // 普通字符（含 "、空格、以及前面有 \ 的 '）在单引号内均字面量
                    sb.append(c)
                }
            }
            sb.append('\'')
        }
        repeat(tail) { sb.append('\\') }
        return sb.toString()
    }
}
