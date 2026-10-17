package com.samge.localaudio.data

/** 字幕悬浮窗状态桥（VM 推送，Service 读取）。 */
object SubtitleBridge {
    @Volatile var text: String? = null
    @Volatile var visible: Boolean = false     // 服务是否应显示悬浮窗
    @Volatile var folded: Boolean = false      // 用户点击折叠

    var onChanged: (() -> Unit)? = null

    fun push(text: String?) {
        this.text = text
        onChanged?.invoke()
    }

    fun setShow(show: Boolean) {
        this.visible = show
        onChanged?.invoke()
    }

    fun toggleFold() {
        folded = !folded
        onChanged?.invoke()
    }
}
