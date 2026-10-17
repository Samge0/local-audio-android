package com.samge.kcourseaudio.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 目录迁移结果。 */
data class MigrateResult(val moved: Int, val skipped: Int, val errors: List<String>)

/**
 * 换音频目录时自动迁移文件：
 * - 旧目录内所有音频按原相对结构搬入新目录
 * - 同名同大小 -> 跳过并删旧；同名不同大小 -> 两者都保留（新文件加 " (n)" 后缀避免覆盖）
 * - 跨分区 rename 失败时回退 copy+delete；迁移后清理残留空目录
 */
object DirMigrator {

    suspend fun migrate(oldDir: File, newDir: File): MigrateResult = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        if (!oldDir.isDirectory || oldDir.canonicalPath == newDir.canonicalPath) {
            return@withContext MigrateResult(0, 0, errors)
        }
        newDir.mkdirs()
        var moved = 0
        var skipped = 0
        val audioFiles = oldDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in Library.AUDIO_EXT }
            .toList()
        for (f in audioFiles) {
            try {
                val rel = f.relativeTo(oldDir)
                var dst = File(newDir, rel.path)
                if (dst.exists()) {
                    if (dst.length() == f.length()) {
                        skipped++
                        f.delete()
                        continue
                    }
                    val base = rel.nameWithoutExtension
                    val ext = rel.extension
                    var i = 1
                    while (dst.exists()) {
                        dst = File(dst.parentFile, base + " (" + i + ")." + ext)
                        i++
                    }
                }
                dst.parentFile?.mkdirs()
                if (!f.renameTo(dst)) {
                    f.copyTo(dst, overwrite = true)
                    f.delete()
                }
                moved++
            } catch (e: Exception) {
                errors.add(f.name + ": " + (e.message ?: "unknown"))
            }
        }
        pruneEmptyDirs(oldDir, keepRoot = true)
        MigrateResult(moved, skipped, errors)
    }

    /** 递归删除空目录；keepRoot=true 时保留 dir 本身。 */
    private fun pruneEmptyDirs(dir: File, keepRoot: Boolean) {
        if (!dir.isDirectory) return
        val subs = dir.listFiles() ?: return
        for (sub in subs) {
            if (sub.isDirectory) pruneEmptyDirs(sub, keepRoot = false)
        }
        if (!keepRoot) {
            val after = dir.listFiles()
            if (after != null) {
                if (after.isEmpty()) {
                    dir.delete()
                }
            }
        }
    }
}
