package com.samge.kcourseaudio.util

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

object TreeUri {

    /**
     * SAF 目录树 URI -> 真实文件路径。
     * content://.../tree/primary:Music/x  ->  /storage/emulated/0/Music/x
     * content://.../tree/XXXX-XXXX:foo    ->  /storage/XXXX-XXXX/foo
     * 识别失败返回 null。
     */
    fun toFilePath(treeUri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val idx = docId.indexOf(':')
            if (idx < 0) return null
            val volume = docId.substring(0, idx)
            val path = docId.substring(idx + 1)
            val base = if (volume == "primary") {
                @Suppress("DEPRECATION")
                Environment.getExternalStorageDirectory().absolutePath
            } else {
                "/storage/" + volume
            }
            if (path.isBlank()) base else File(base, path).absolutePath
        } catch (e: Exception) {
            null
        }
    }
}
