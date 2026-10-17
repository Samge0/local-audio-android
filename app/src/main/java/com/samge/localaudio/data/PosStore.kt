package com.samge.localaudio.data

import android.content.Context

/** 每首曲目的播放进度记忆（对齐 Web 版 localStorage ka.pos.<id>）。 */
class PosStore(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("ka_pos", Context.MODE_PRIVATE)

    fun get(id: String): Int = sp.getInt(key(id), 0)

    fun put(id: String, sec: Int) {
        if (sec >= 1) sp.edit().putInt(key(id), sec).apply()
    }

    fun clear(id: String) = sp.edit().remove(key(id)).apply()

    private fun key(id: String) = "pos.$id"
}
