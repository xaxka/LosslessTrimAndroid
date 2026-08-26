package com.xixka.losslesstrim

import com.xixka.losslesstrim.data.StreamInfo
import com.xixka.losslesstrim.data.matchDroppedBySignature
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 跨文件"丢弃轨道"签名匹配单测（Models.matchDroppedBySignature）：
 * 全局流索引是文件内位置，各文件轨道排列不同，同一索引指向不同内容；
 * "应用到全部"必须按轨道签名（类型/编码/语言/标题/声道）找同款轨，
 * 不能直接把索引下发到其他文件。
 */
class TrackMatchTest {

    private fun s(
        index: Int,
        type: String,
        codec: String,
        language: String? = null,
        title: String? = null,
        channels: Int? = null,
    ) = StreamInfo(
        index = index, codecType = type, codecName = codec,
        language = language, title = title, channels = channels, channelLayout = null,
        sampleRate = null, bitRate = null,
        width = null, height = null, attachedPic = false,
    )

    /** 典型场景：源文件音轨在前字幕在后，目标文件字幕在前音轨在后（索引错位） */
    @Test
    fun `same track at different index dropped by signature`() {
        val source = listOf(
            s(0, "video", "h264"),
            s(1, "audio", "aac", "jpn"),
            s(2, "audio", "ac3", "eng", "Commentary", 6),
            s(3, "subtitle", "ass", "eng"),
        )
        val target = listOf(
            s(0, "video", "h264"),
            s(1, "subtitle", "ass", "eng"),
            s(2, "audio", "aac", "jpn"),
            s(3, "audio", "ac3", "eng", "Commentary", 6),
        )
        // 源勾掉评论音轨（index=2）→ 目标文件里它是 index=3，按签名命中
        assertEquals(setOf(3), matchDroppedBySignature(source, setOf(2), target))
    }

    /** 索引相同但内容不同的轨（源 index=3 是字幕，目标 index=3 是音轨）不许误伤 */
    @Test
    fun `same index different track not dropped`() {
        val source = listOf(
            s(0, "video", "h264"),
            s(1, "audio", "aac", "jpn"),
            s(2, "subtitle", "ass", "eng"),
            s(3, "subtitle", "ass", "chi"),
        )
        val target = listOf(
            s(0, "video", "h264"),
            s(1, "audio", "aac", "jpn"),
            s(2, "audio", "aac", "eng"),
            s(3, "audio", "ac3", "eng"),
        )
        // 勾掉源的中文字幕（index=3）→ 目标没有同款轨，什么都不丢
        assertEquals(emptySet<Int>(), matchDroppedBySignature(source, setOf(3), target))
    }

    /** 目标文件缺同款轨（如无评论轨的集数）→ 保持不动 */
    @Test
    fun `target without matching track unchanged`() {
        val source = listOf(
            s(0, "video", "h264"),
            s(1, "audio", "aac", "jpn"),
            s(2, "audio", "ac3", "eng", "Commentary", 6),
        )
        val target = listOf(
            s(0, "video", "h264"),
            s(1, "audio", "aac", "jpn"),
        )
        assertEquals(emptySet<Int>(), matchDroppedBySignature(source, setOf(2), target))
    }

    /** 目标文件多条同签名轨 → 全部匹配（批量一致性优先） */
    @Test
    fun `multiple identical tracks all matched`() {
        val source = listOf(
            s(0, "video", "h264"),
            s(1, "audio", "aac", "eng"),
        )
        val target = listOf(
            s(0, "video", "h264"),
            s(1, "audio", "aac", "jpn"),
            s(2, "audio", "aac", "eng"),
            s(3, "audio", "aac", "eng"),
        )
        assertEquals(setOf(2, 3), matchDroppedBySignature(source, setOf(1), target))
    }

    /** 语言 und 与 null 归一为同值（ffprobe 与 MediaExtractor 上报不一致） */
    @Test
    fun `und language normalized to null`() {
        val source = listOf(
            s(0, "video", "h264"),
            s(1, "subtitle", "ass", "und"),
        )
        val target = listOf(
            s(0, "video", "h264"),
            s(1, "subtitle", "ass", null),
        )
        assertEquals(setOf(1), matchDroppedBySignature(source, setOf(1), target))
    }

    /** 声道数参与签名：同为 aac/eng 的 2.0 与 5.1 是两条不同的轨 */
    @Test
    fun `channels distinguish otherwise identical tracks`() {
        val source = listOf(
            s(0, "video", "h264"),
            s(1, "audio", "aac", "eng", channels = 2),
            s(2, "audio", "aac", "eng", channels = 6),
        )
        val target = listOf(
            s(0, "video", "h264"),
            s(1, "audio", "aac", "eng", channels = 6),
            s(2, "audio", "aac", "eng", channels = 2),
        )
        // 勾掉源 5.1 轨（index=2）→ 目标按签名只丢它的 5.1 轨（index=1）
        assertEquals(setOf(1), matchDroppedBySignature(source, setOf(2), target))
    }

    /** 空丢弃集 → 空结果（零开销短路） */
    @Test
    fun `empty dropped yields empty`() {
        val source = listOf(s(0, "video", "h264"))
        assertEquals(emptySet<Int>(), matchDroppedBySignature(source, emptySet(), source))
    }
}
