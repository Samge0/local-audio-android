package com.samge.kcourseaudio.data

import java.io.File
import java.util.Locale

/** 一条字幕。 */
data class Cue(val startMs: Long, val endMs: Long, val text: String)

/** 字幕文件解析（SRT / VTT / ASS / SSA），纯 Kotlin 无依赖。 */
object Subtitle {

    fun parse(file: File): List<Cue> = parseText(file.readText(Charsets.UTF_8))

    fun parseText(raw: String): List<Cue> {
        if (raw.contains("[Script Info]") || raw.contains("Dialogue:")) {
            return parseAss(raw)
        }
        val cues = mutableListOf<Cue>()
        val body = raw.replace("\r\n", "\n").replace("\r", "\n")
        // 按空行分块（保留块内行结构）
        val blocks = mutableListOf<List<String>>()
        var cur = mutableListOf<String>()
        for (ln in body.lines()) {
            if (ln.isBlank()) {
                if (cur.isNotEmpty()) { blocks += cur; cur = mutableListOf() }
            } else {
                cur += ln
            }
        }
        if (cur.isNotEmpty()) blocks += cur

        for (lines in blocks) {
            val tIdx = lines.indexOfFirst { it.contains("-->") }
            if (tIdx == -1) continue
            val times = lines[tIdx].split("-->")
            if (times.size < 2) continue
            val s = parseTs(times[0].trim())
            val e = parseTs(times[1].trim().substringBefore(" ").trim())
            if (s == null || e == null) continue
            val text = lines.drop(tIdx + 1).joinToString(" ").trim()
            if (text.isNotEmpty()) cues += Cue(s, e, cleanText(text))
        }
        return cues.sortedBy { it.startMs }
    }

    /** ASS/SSA Dialogue 解析。 */
    private fun parseAss(raw: String): List<Cue> {
        val cues = mutableListOf<Cue>()
        var textIdx = 9
        var startIdx = 1
        var endIdx = 2
        for (line in raw.lines()) {
            val t = line.trim()
            if (t.startsWith("Format:", ignoreCase = true) && t.contains("Text")) {
                val fields = t.removePrefix("Format:").split(",").map { it.trim().lowercase(Locale.US) }
                fields.indexOf("text").let { if (it >= 0) textIdx = it }
                fields.indexOf("start").let { if (it >= 0) startIdx = it }
                fields.indexOf("end").let { if (it >= 0) endIdx = it }
            } else if (t.startsWith("Dialogue:", ignoreCase = true)) {
                val body = t.removePrefix("Dialogue:").trim()
                val parts = body.split(",", limit = textIdx + 1)
                if (parts.size <= textIdx) continue
                val sMs = parseTs(parts[startIdx].trim())
                val eMs = parseTs(parts[endIdx].trim())
                if (sMs == null || eMs == null) continue
                val text = parts[textIdx]
                    .replace("\\N", " ").replace("\\n", " ")
                if (text.isNotBlank()) cues += Cue(sMs, eMs, cleanText(text.trim()))
            }
        }
        return cues.sortedBy { it.startMs }
    }

    /** 00:01:02.500 / 00:01:02,500 / 01:02.5 → ms。 */
    private fun parseTs(ts: String): Long? {
        val t = ts.replace(',', '.')
        val parts = t.split(':')
        if (parts.size !in 2..3) return null
        val h = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        val m = parts[parts.size - 2].toLongOrNull() ?: return null
        val sec = parts[parts.size - 1].toDoubleOrNull() ?: return null
        return ((h * 3600 + m * 60) * 1000 + sec * 1000).toLong()
    }

    /** 去掉 HTML 标签与 ASS 样式标记。 */
    private fun cleanText(s: String): String =
        s.replace(Regex("<[^>]+>"), "").replace(Regex("\\{[^}]*\\}"), "").trim()

    /** 当前时间对应的 cue 文本。 */
    fun currentText(cues: List<Cue>, posMs: Long): String? {
        var lo = 0
        var hi = cues.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (cues[mid].startMs <= posMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (ans == -1) return null
        val c = cues[ans]
        return if (posMs <= c.endMs) c.text else null
    }
}
