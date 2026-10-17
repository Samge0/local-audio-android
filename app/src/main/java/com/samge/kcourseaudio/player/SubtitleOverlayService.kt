package com.samge.kcourseaudio.player

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.samge.kcourseaudio.data.SubtitleBridge

/**
 * 字幕悬浮窗：半透明圆角小窗显示当前字幕；点击折叠成小圆点/再点展开；可拖动。
 * 未授权悬浮窗权限时静默退出。
 */
class SubtitleOverlayService : Service() {

    private var wm: WindowManager? = null
    private var textView: TextView? = null
    private var dot: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildViews()
        SubtitleBridge.onChanged = { refresh() }
        refresh()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildViews() {
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

        val tv = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            maxLines = 3
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xF01A1C26.toInt())
                setStroke(dp(1), 0x50FFFFFF.toInt())   // 细白描边：深色背景下也清晰
            }
            visibility = View.GONE
        }
        textView = tv

        val d = TextView(this).apply {
            text = "\u25CF"   // ● 圆点字符，必渲染
            textSize = 18f
            setTextColor(0xFF5472F0.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
            visibility = View.GONE
        }
        dot = d

        // 统一拖动+点击
        var startX = 0f; var startY = 0f; var moved = false
        val touch = View.OnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ev.rawX; startY = ev.rawY; moved = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - startX
                    val dy = ev.rawY - startY
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) moved = true
                    if (moved) moveBy(v, dx.toInt(), dy.toInt())
                    startX = ev.rawX; startY = ev.rawY
                    moved
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) SubtitleBridge.toggleFold()
                    false
                }
                else -> false
            }
        }
        tv.setOnTouchListener(touch)
        d.setOnTouchListener(touch)

        // 两个窗都先加入（GSTATE GONE），refresh 切换
        runCatching {
            wm?.addView(tv, WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(170)
            })
            wm?.addView(d, WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dp(14); y = dp(190)
            })
        }
    }

    private fun moveBy(v: View, dx: Int, dy: Int) {
        val p = v.layoutParams as? WindowManager.LayoutParams ?: return
        p.x += dx
        p.y -= dy
        runCatching { wm?.updateViewLayout(v, p) }
    }

    private fun refresh() {
        val text = SubtitleBridge.text
        val showText = SubtitleBridge.visible && !SubtitleBridge.folded && !text.isNullOrEmpty()
        val showDot = SubtitleBridge.visible && SubtitleBridge.folded
        textView?.let { tv ->
            if (showText) tv.text = text
            tv.visibility = if (showText) View.VISIBLE else View.GONE
        }
        dot?.let { d ->
            d.visibility = if (showDot) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroy() {
        SubtitleBridge.onChanged = null
        runCatching { textView?.let { wm?.removeView(it) } }
        runCatching { dot?.let { wm?.removeView(it) } }
        super.onDestroy()
    }
}
