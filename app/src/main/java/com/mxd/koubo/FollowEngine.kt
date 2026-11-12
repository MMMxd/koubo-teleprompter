package com.mxd.koubo

/**
 * 语音跟随引擎：把 ASR 识别出的文本对齐到稿件，算出「念到哪了」。
 *
 * 两个独立缓冲区：
 *  - scriptNorm：稿件归一化文本（只保留文字/数字，去标点空白），scriptMap 记录每个字对应的原文偏移
 *  - recNorm  ：识别文本归一化缓冲（增量追加，超长截头）
 *
 * tryAdvance：取识别文本尾部窗口（最长30字，最短3字），在稿件当前位置附近找最长匹配；
 * pos 只前进不后退，念错重读时保持原地等识别追上来。
 */
class FollowEngine(script: String) {

    private val scriptNorm = StringBuilder()
    private val scriptMap = ArrayList<Int>()
    private val recNorm = StringBuilder()
    private var rawLen = 0

    var pos = 0
        private set

    init {
        var i = 0
        while (i < script.length) {
            val c = script[i]
            if (isKept(c)) {
                scriptNorm.append(c.lowercaseChar())
                scriptMap.add(i)
            }
            i++
        }
    }

    val progress: Float
        get() = if (scriptNorm.isEmpty()) 0f else pos.toFloat() / scriptNorm.length

    /** 当前念到的位置在原文中的字符偏移 */
    fun originalOffset(): Int =
        if (scriptMap.isEmpty()) 0 else scriptMap[pos.coerceIn(0, scriptMap.size - 1)]

    /** 重新从头开始跟随 */
    fun reset() {
        pos = 0
    }

    /** 喂入 ASR 的全量识别文本（历史段+当前段），增量归一化 */
    fun feed(raw: String) {
        if (raw.length > rawLen) {
            var i = rawLen
            while (i < raw.length) {
                val c = raw[i]
                if (isKept(c)) recNorm.append(c.lowercaseChar())
                i++
            }
            rawLen = raw.length
            // 只留尾部上下文，防长时间口播内存无限涨
            if (recNorm.length > 600) recNorm.delete(0, recNorm.length - 300)
        }
        tryAdvance()
    }

    private fun tryAdvance() {
        val n = recNorm.length
        if (n == 0) return
        var p = pos
        var k = minOf(30, n)
        while (k >= 3) {
            val candStart = n - k
            val idx = indexOfDraftItem(recNorm, candStart, k, (p - 4).coerceAtLeast(0), p + 80)
            if (idx >= 0 && idx + k >= p) {
                p = idx + k
                break
            }
            k--
        }
        if (p > pos) pos = p
    }

    /** 在 scriptNorm 里搜索 src[srcStart..<srcStart+len]，搜索范围 [from, to] */
    private fun indexOfDraftItem(src: StringBuilder, srcStart: Int, len: Int, from: Int, to: Int): Int {
        val lastIdx = scriptNorm.length - len
        var i = from
        while (i <= lastIdx && i <= to) {
            var j = 0
            while (j < len && scriptNorm[i + j] == src[srcStart + j]) j++
            if (j == len) return i
            i++
        }
        return -1
    }

    private fun isKept(c: Char): Boolean = Character.isLetterOrDigit(c)
}
