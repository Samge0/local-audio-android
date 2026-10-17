package com.samge.kcourseaudio.data

import com.samge.kcourseaudio.util.Fmt
import java.io.File
import java.util.Locale

/** 一首曲目（对齐 Web 版 /api/list 条目：含 sub 同名字幕路径）。 */
data class Track(
    val id: String,      // 相对音频目录的路径（/ 分隔）
    val name: String,    // 不含扩展名
    val group: String,   // 子目录（" / " 连接），顶层为空串
    val size: Long,
    val ext: String,
    val sub: File? = null,   // 同名字幕，无则 null
)

/** 扫描音频目录；分组间与组内都自然排序（对齐 Web 版 scan_audio）。 */
object Library {

    val AUDIO_EXT = setOf("mp3", "m4a", "aac", "ogg", "wav", "flac", "opus")
    val SUB_EXTS = listOf("vtt", "srt", "ass", "ssa")

    fun scanSorted(dir: File): List<Track> {
        val root = dir.absolutePath
        val items = mutableListOf<Track>()
        if (!dir.isDirectory) return items
        val stack = ArrayDeque<File>()
        stack.add(dir)
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            val files = d.listFiles() ?: continue
            for (f in files) {
                if (f.isDirectory) {
                    stack.add(f)
                } else if (f.isFile && f.extension.lowercase() in AUDIO_EXT) {
                    val rel = f.absolutePath.removePrefix(root)
                        .replace(File.separatorChar, '/').trimStart('/')
                    val parts = rel.split('/')
                    items += Track(
                        id = rel,
                        name = parts.last().substringBeforeLast('.'),
                        group = if (parts.size > 1) parts.dropLast(1).joinToString(" / ") else "",
                        size = f.length(),
                        ext = f.extension.lowercase(),
                        sub = findSub(f),
                    )
                }
            }
        }
        val byGroup = items.groupBy { it.group }
        val result = mutableListOf<Track>()
        val groups = byGroup.keys.toMutableList()
        groups.sortWith { a, b -> Fmt.compareNatural(a, b) }
        for (g in groups) {
            val tracks = byGroup[g] ?: continue
            result.addAll(tracks.sortedWith { a, b -> Fmt.compareNatural(a.name, b.name) })
        }
        return result
    }

    /**
     * 找同目录字幕：
     * 1) 完全同名（忽略大小写）：第01讲.srt
     * 2) 语言/说明后缀：第01讲.zh.srt / 第01讲-字幕.srt / 第01讲 (1).srt
     * 优先级 vtt > srt > ass > ssa。
     */
    fun findSub(audio: File): File? {
        val parent = audio.parentFile ?: return null
        val stemLc = audio.nameWithoutExtension.lowercase(Locale.ROOT)
        val candidates = parent.listFiles()
            ?.filter { it.isFile && it.extension.lowercase(Locale.ROOT) in SUB_EXTS }
            ?: return null
        if (candidates.isEmpty()) return null
        val byExt = SUB_EXTS.associateWith { ext ->
            candidates.filter { it.extension.lowercase(Locale.ROOT) == ext }
        }
        for (ext in SUB_EXTS) {
            for (f in byExt[ext].orEmpty()) {
                if (f.nameWithoutExtension.lowercase(Locale.ROOT) == stemLc) return f
            }
        }
        for (ext in SUB_EXTS) {
            for (f in byExt[ext].orEmpty()) {
                val n = f.nameWithoutExtension.lowercase(Locale.ROOT)
                if (n.length > stemLc.length && n.startsWith(stemLc)) {
                    val c = n[stemLc.length]
                    if (c == '.' || c == '-' || c == '_' || c == ' ' || c == '(') return f
                }
            }
        }
        return null
    }

    /** 解析 id 到真实文件；防目录穿越。 */
    fun resolve(dir: File, id: String): File? {
        if (id.isBlank()) return null
        val f = File(dir, id)
        val rootPath = dir.canonicalFile.absolutePath
        val filePath = f.canonicalFile.absolutePath
        if (!filePath.startsWith(rootPath)) return null
        return if (f.isFile && f.extension.lowercase() in AUDIO_EXT) f else null
    }
}
