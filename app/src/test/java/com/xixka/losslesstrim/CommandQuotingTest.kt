package com.xixka.losslesstrim

import com.xixka.losslesstrim.util.CommandQuoting
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [CommandQuoting.quoteArg] 单测。
 *
 * ffmpeg-kit 的 FFmpegKitConfig 有静态块加载 native 库，JVM 单测无法直接调
 * parseArguments，故这里将其**逐行等价移植**（2.2.1 源码，见
 * util/CommandQuoting.kt 注释）作参照实现：quoteArg 的产物经它解析后，
 * 必须恰好还原成单个原始路径参数。
 */
class CommandQuotingTest {

    // ---- ffmpeg-kit FFmpegKitConfig.parseArguments 等价移植（勿改语义）----
    private fun parseArguments(command: String): List<String> {
        val argumentList = ArrayList<String>()
        val currentArgument = StringBuilder()
        var singleQuoteStarted = false
        var doubleQuoteStarted = false
        for (i in command.indices) {
            val previousChar: Char? = if (i > 0) command[i - 1] else null
            val currentChar = command[i]
            if (currentChar == ' ') {
                if (singleQuoteStarted || doubleQuoteStarted) {
                    currentArgument.append(currentChar)
                } else if (currentArgument.isNotEmpty()) {
                    argumentList.add(currentArgument.toString())
                    currentArgument.setLength(0)
                }
            } else if (currentChar == '\'' && (previousChar == null || previousChar != '\\')) {
                if (singleQuoteStarted) {
                    singleQuoteStarted = false
                } else if (doubleQuoteStarted) {
                    currentArgument.append(currentChar)
                } else {
                    singleQuoteStarted = true
                }
            } else if (currentChar == '"' && (previousChar == null || previousChar != '\\')) {
                if (doubleQuoteStarted) {
                    doubleQuoteStarted = false
                } else if (singleQuoteStarted) {
                    currentArgument.append(currentChar)
                } else {
                    doubleQuoteStarted = true
                }
            } else {
                currentArgument.append(currentChar)
            }
        }
        if (currentArgument.isNotEmpty()) {
            argumentList.add(currentArgument.toString())
        }
        return argumentList
    }

    /** 完整命令（拼接 -i 前后缀）经解析器后，路径必须恰为一个独立参数且逐字符还原 */
    private fun assertRoundTrip(path: String) {
        val cmd = "-hide_banner -y -i ${CommandQuoting.quoteArg(path)} -c copy out.mkv"
        val args = parseArguments(cmd)
        assertEquals("argv 形态: $cmd -> $args", listOf("-hide_banner", "-y", "-i", path, "-c", "copy", "out.mkv"), args)
    }

    @Test
    fun `simple path keeps legacy double-quote form`() {
        assertEquals("\"/in/a b.mp4\"", CommandQuoting.quoteArg("/in/a b.mp4"))
        // 单引号在双引号内本是字面量，仍走简单分支
        assertEquals("\"it's fine.mp4\"", CommandQuoting.quoteArg("it's fine.mp4"))
        assertEquals("\"a\\b.mp4\"", CommandQuoting.quoteArg("a\\b.mp4"))
        assertRoundTrip("/in/a b.mp4")
        assertRoundTrip("it's fine.mp4")
    }

    @Test
    fun `path with double quote survives as one argv`() {
        // 合法文件名 a"b.mp4：旧拼接会把命令拆坏，这里必须完整还原
        assertRoundTrip("""a"b.mp4""")
        assertRoundTrip("""pre "post.mp4""")
        assertRoundTrip(""""lead.mp4""")
        assertRoundTrip("""trail".mp4""")
        assertRoundTrip("""a"b"c.mp4""")
    }

    @Test
    fun `injection attempt stays inert`() {
        // 恶意文件名试图逃逸注入 -y 覆盖任意目标：quote 后恰为一个参数
        assertRoundTrip("""x" -y /sdcard/受害目标.mp4""")
        assertRoundTrip("""x' -y /sdcard/受害目标.mp4""")
        assertRoundTrip("""-"-map 0 -y out.mp4""")
        assertRoundTrip("""a " b " c -movflags faststart.mp4""")
    }

    @Test
    fun `mixed quotes and backslashes round-trip`() {
        assertRoundTrip("""a'"b.mp4""")
        assertRoundTrip("""'".mp4""")
        assertRoundTrip("'''")
        assertRoundTrip("""a\'b.mp4""")
        assertRoundTrip("""a\\'b.mp4""")
        assertRoundTrip("""a\"b.mp4""")
        assertRoundTrip("trail\\ .mp4")            // 末尾反斜杠+空格
        assertRoundTrip("trail\\\\")               // 末尾两个反斜杠
        assertRoundTrip("onlybackslash\\")
        assertRoundTrip("\\\\\\")                  // 纯反斜杠
        assertRoundTrip("\\\"' \\ .mp4")           // 全特殊字符混合
    }

    @Test
    fun `quote sandwich emits exact expected token`() {
        // 三明治形态锁定：' 关单引号 + "'" + ' 重开单引号。
        // 注意：只有含 "（复杂路径才进单引号方案）且含 ' 的路径才出现三明治；
        // 仅含 ' 的路径走简单双引号分支（' 在双引号内本就是字面量）
        assertEquals("""'a'"'"'"b.mp4'""", CommandQuoting.quoteArg("""a'"b.mp4"""))
        assertEquals("\"a'b.mp4\"", CommandQuoting.quoteArg("""a'b.mp4"""))
        assertEquals("""'a"b.mp4'""", CommandQuoting.quoteArg("""a"b.mp4"""))
    }

    @Test
    fun `empty path does not inject`() {
        assertEquals("\"\"", CommandQuoting.quoteArg(""))
    }
}
