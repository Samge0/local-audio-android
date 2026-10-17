package com.samge.kcourseaudio.util

import java.util.Locale

/** 时间/大小格式化 + 自然排序（与 Web 版一致的语义）。 */
object Fmt {

    fun time(totalSecIn: Long): String {
        val s = if (totalSecIn < 0) 0L else totalSecIn
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }

    fun size(n: Long): String = when {
        n >= 1_000_000_000L -> String.format(Locale.US, "%.2f GB", n / 1e9)
        n >= 1_000_000L -> String.format(Locale.US, "%.1f MB", n / 1e6)
        else -> String.format(Locale.US, "%.0f KB", n / 1e3)
    }

    /** "第02讲" 与 "第10讲" 按数值比较；字母忽略大小写。 */
    fun compareNatural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                val cmp = if (na.length != nb.length) na.length - nb.length else na.compareTo(nb)
                if (cmp != 0) return cmp
                i = ei
                j = ej
            } else {
                val la = ca.lowercaseChar()
                val lb = cb.lowercaseChar()
                if (la != lb) return la.code - lb.code
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
