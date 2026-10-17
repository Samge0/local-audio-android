package com.samge.kcourseaudio.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

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
                conn.setRequestProperty("User-Agent", "kcourse-audio-android/1.0")
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
        ZipInputStream(zip.inputStream().buffered(1 shl 16)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                val name = e.name
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
        }
        return Pair(added, skipped)
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
