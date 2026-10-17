package com.samge.localaudio.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import java.nio.charset.Charset

/** 导入状态（UI 订阅）。 */
sealed class ImportState {
    data object Idle : ImportState()
    data class Running(val phase: String, val progress: Float, val detail: String) : ImportState()
    data class Done(val added: Int, val skipped: Int, val bytes: Long, val targetDir: String) : ImportState()
    data class Failed(val message: String) : ImportState()
}

/**
 * 从 URL 下载 zip 并解压到音频目录（保持 zip 内目录结构）。
 * 支持 GitHub release 直链（302 跟随、跨协议跳转）、大文件流式下载（不整包进内存）。
 */
class ZipImporter(
    private val settings: SettingsStore,
    private val onState: (ImportState) -> Unit,
) {
    suspend fun import(urlStr: String, subDir: String = "") = withContext(Dispatchers.IO) {
        var tmp: File? = null
        try {
            onState(ImportState.Running("下载", 0f, "连接中…"))
            tmp = File.createTempFile("kaudio_import_", ".zip")
            val bytes = downloadTo(urlStr, tmp)
            onState(ImportState.Running("解压", 0f, "校验 zip…"))
            val target = File(settings.audioDir(), subDir)
            val (added, skipped) = unzipInto(tmp, target, bytes)
            onState(ImportState.Done(added, skipped, bytes, target.absolutePath))
        } catch (e: Exception) {
            Log.e("ZipImporter", "import failed", e)
            onState(ImportState.Failed(e.message ?: "导入失败"))
        } finally {
            tmp?.delete()
        }
    }

    /** 流式下载 + 进度回调；返回总字节数。手动跟随重定向（支持 http->https 跨协议跳转）。 */
    private fun downloadTo(urlStr: String, dst: File): Long {
        var url = URL(urlStr)
        var redirects = 0
        while (true) {
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.setRequestProperty("User-Agent", "local-audio-android/1.0")
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                    code == HttpURLConnection.HTTP_MOVED_TEMP ||
                    code == 307 || code == 308) {
                    val loc = conn.getHeaderField("Location")
                        ?: throw IllegalStateException("重定向缺少 Location")
                    redirects++
                    if (redirects > 5) throw IllegalStateException("重定向次数过多")
                    url = URL(url, loc)
                    continue
                }
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                val total = conn.contentLengthLong
                var read = 0L
                var lastPct = -1
                conn.inputStream.use { ins ->
                    FileOutputStream(dst).use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val pct = ((read * 100) / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onState(ImportState.Running(
                                        "下载", pct / 100f,
                                        fmtBytes(read) + " / " + fmtBytes(total)))
                                }
                            }
                        }
                    }
                }
                return read
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun unzipInto(zip: File, target: File, bytes: Long): Pair<Int, Int> {
        // zip 魔数校验
        zip.inputStream().use { ins ->
            val m = ByteArray(4)
            if (ins.read(m) < 4 || m[0] != 'P'.code.toByte() || m[1] != 'K'.code.toByte()) {
                throw IllegalArgumentException("下载内容不是 zip 文件")
            }
        }
        target.mkdirs()
        var added = 0
        var skipped = 0
        // Windows 资源管理器打包的 zip：文件名为系统代码页（中文=GBK）且不置 UTF-8 标志位。
        // ZipInputStream 的 charset 只作用于"无 UTF-8 标志"的条目；带标志的仍按 UTF-8 解。
        // 因此固定传 GBK：Windows 中文包正确解码，标准 UTF-8 包（GitHub release 等）不受影响。
        val zis = ZipInputStream(zip.inputStream().buffered(1 shl 16), zipCharset)
        try {
            while (true) {
                val e = zis.nextEntry ?: break
                val name = fixZipName(e.name)
                if (e.isDirectory) {
                    if (isSafe(name)) File(target, name).mkdirs()
                    zis.closeEntry()
                    continue
                }
                val base = name.substringAfterLast('/')
                val ext = base.substringAfterLast('.', "").lowercase()
                if (!isSafe(name) || base.startsWith("._") || base.startsWith(".")
                    || name.contains("__MACOSX")
                    || (ext !in Library.AUDIO_EXT && ext !in Library.SUB_EXTS)) {
                    zis.closeEntry()
                    continue
                }
                val outFile = File(target, name)
                outFile.parentFile?.mkdirs()
                if (outFile.exists() && outFile.length() == e.size) {
                    skipped++
                } else {
                    FileOutputStream(outFile).use { out -> zis.copyTo(out) }
                    added++
                }
                zis.closeEntry()
            }
        } finally {
            zis.close()
        }
        return Pair(added, skipped)
    }

    /**
     * Windows 资源管理器打包的 zip：文件名是 GBK 编码，且不置 UTF-8 标志位。
     * 已在 unzipInto 中直接用 GBK charset 构造 ZipInputStream（仅影响无标志条目）。
     */
    private val zipCharset: Charset
        get() = Charset.forName("GBK")

    /**
     * Android libcore 的 ZipInputStream 对无 UTF-8 标志的条目名固定按 CP437 解码
     * （构造器 charset 参数在该路径不生效），Windows GBK 中文名因此显示为 ╓╨╬─ 类乱码。
     * 修复：把解出的名字编回 CP437 得到原始字节，再按 GBK 解码。
     * 纯 ASCII（含正常英文路径）与含替换符的名字原样返回。
     */
    companion object {
        /** CP437→GBK 文件名修复（unzipLocal 等外部路径复用）。 */
        fun fixZipName(name: String): String {
        if (name.isEmpty() || name.all { it.code in 32..126 }) return name
        if (name.contains('�')) return name
        return try {
            // libcore 按 CP437 解出的字符（如 U+2553 ╓）不在 Latin-1，
            // 必须用 CP437 编回原始字节，再按 GBK 解码
            val cp437 = Charset.forName("Cp437")
            val gbk = Charset.forName("GBK")
            val raw = name.toByteArray(cp437)
            val fixed = String(raw, gbk)
            // GBK 解出的名字必须比 CP437 版本更"像中文"：含 CJK 字符才采用
            if (fixed.any { it.code in 0x4E00..0x9FFF }) fixed else name
        } catch (ex: Exception) {
            name
        }
        }
    }

    private fun isSafe(name: String): Boolean {
        if (name.startsWith("/") || name.contains("..")) return false
        val canonical = File(name).canonicalFile.absolutePath
        val root = File("/").absolutePath
        return true  // 相对路径不会穿越；上面已挡 ../ 与绝对路径
    }

    private fun fmtBytes(n: Long): String {
        val kb = n / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> String.format("%.2f GB", gb)
            mb >= 1 -> String.format("%.1f MB", mb)
            else -> String.format("%.0f KB", kb)
        }
    }
}
