package com.samge.localaudio

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 兜底清理：历史导入中断可能遗留的临时 zip（下载/本地导入的 tmp 都在 cacheDir）
        Thread {
            runCatching {
                cacheDir.listFiles()?.forEach {
                    if (it.name.startsWith("kaudio_import_") || it.name.startsWith("kaudio_local_")) {
                        it.delete()
                    }
                }
            }
        }.start()
    }
}
