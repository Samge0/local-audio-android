package com.samge.localaudio.data

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/** 统一配置入口（对齐 Web 版 config.py）：音频目录、播放模式、倍速、上次曲目。 */
class SettingsStore(context: Context) {
    private val app = context.applicationContext
    private val sp = app.getSharedPreferences("ka_settings", Context.MODE_PRIVATE)

    /** 应用专属默认目录（无需任何存储权限）。 */
    fun defaultDir(): File =
        app.getExternalFilesDir(null)?.let { File(it, "audio") } ?: File(app.filesDir, "audio")

    /** 当前音频目录（未自定义时为默认目录，自动创建）。 */
    fun audioDir(): File {
        val p = sp.getString(KEY_DIR, "") ?: ""
        return if (p.isNotBlank()) File(p) else defaultDir().also { it.mkdirs() }
    }

    fun isCustomDir(): Boolean = !(sp.getString(KEY_DIR, "") ?: "").isBlank()

    /** 目录是否在应用私有区域内（该区域内不需要存储权限）。 */
    fun inAppScoped(dir: File): Boolean {
        val priv = listOfNotNull(
            app.filesDir.absolutePath,
            app.cacheDir.absolutePath,
            app.getExternalFilesDir(null)?.absolutePath
        )
        return priv.any { dir.canonicalFile.absolutePath.startsWith(it) }
    }

    /** 自定义目录是否已具备写入权限（Android 11+ 需"所有文件访问"）。 */
    fun hasStoragePerm(dir: File): Boolean {
        if (inAppScoped(dir)) return true
        return if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else sp.getBoolean(KEY_LEGACY_PERM, false)
    }

    fun noteLegacyPermGranted() = sp.edit().putBoolean(KEY_LEGACY_PERM, true).apply()

    var dirPath: String
        get() = sp.getString(KEY_DIR, "") ?: ""
        set(v) = sp.edit().putString(KEY_DIR, v).apply()

    var mode: String
        get() = sp.getString(KEY_MODE, "seq") ?: "seq"
        set(v) = sp.edit().putString(KEY_MODE, v).apply()

    var rate: Float
        get() = sp.getFloat(KEY_RATE, 1f)
        set(v) = sp.edit().putFloat(KEY_RATE, v).apply()

    var subOn: Boolean
        get() = sp.getBoolean(KEY_SUB_ON, true)
        set(v) = sp.edit().putBoolean(KEY_SUB_ON, v).apply()

    fun collapsedGroups(): Set<String> = sp.getStringSet(KEY_COLLAPSED, emptySet()) ?: emptySet()

    fun saveCollapsed(groups: Set<String>) {
        sp.edit().putStringSet(KEY_COLLAPSED, groups).apply()
    }

    var curId: String?
        get() = sp.getString(KEY_CUR, null)
        set(v) = sp.edit().putString(KEY_CUR, v).apply()

    private companion object {
        const val KEY_DIR = "audio_dir"
        const val KEY_MODE = "mode"
        const val KEY_RATE = "rate"
        const val KEY_CUR = "cur"
        const val KEY_LEGACY_PERM = "legacy_perm"
        const val KEY_SUB_ON = "sub_on"
        const val KEY_COLLAPSED = "collapsed_groups"
    }
}
