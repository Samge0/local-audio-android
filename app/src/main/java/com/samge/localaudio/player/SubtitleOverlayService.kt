package com.samge.localaudio.player

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.samge.localaudio.data.SubtitleBridge
import kotlin.math.abs

/**
 * 字幕悬浮窗（BiTrans OverlayService 同款交互）：
 * - 单容器：展开 = 圆角深色窗显示字幕；点击折叠 = 34dp 半圆 tab 吸附最近屏幕边缘
 * - 拖动改位置；点击 tab 重新展开
 * - 字幕文本变化时整窗保持稳定（固定最大宽度，不随每句伸缩跳动）
 */
class SubtitleOverlayService : Service() {

    private var wm: WindowManager? = null
    private var container: LinearLayout? = null
    private var textView: TextView? = null
    private var tabView: TextView? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        createOverlay()
        SubtitleBridge.onChanged = { refresh() }
        refresh()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlay() {
        SubtitleBridge.folded = false   // 服务（重）启动 = 新会话，恢复展开形态
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

        val lp = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.86f).toInt(),  // 固定宽度：文本变化不跳动
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.BOTTOM or Gravity.START
        lp.x = (resources.displayMetrics.widthPixels * 0.07f).toInt()
        lp.y = dp(150)

        val tv = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            maxLines = 3
        }
        textView = tv

        val tab = TextView(this).apply {
            text = "▶"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(220, 0x27, 0x27, 0x29))
            }
        }
        tabView = tab

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(11), dp(16), dp(11))
            addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xF01A1C26.toInt())
                setStroke(dp(1), 0x50FFFFFF.toInt())
            }
        }
        container = box

        var downX = 0f; var downY = 0f
        var startLpX = 0; var startLpY = 0
        var moved = false
        var collapsed = false
        var lastExpandedX = lp.x
        var lastExpandedY = lp.y

        fun applyCollapsed(c: Boolean) {
            collapsed = c
            SubtitleBridge.folded = c
            if (c) {
                lastExpandedX = lp.x
                lastExpandedY = lp.y
                box.removeAllViews()
                box.addView(tab, LinearLayout.LayoutParams(dp(38), dp(38)))
                box.setPadding(0, 0, 0, 0)
                box.background = null
                val dm = resources.displayMetrics
                // 吸附到最近的屏幕边缘
                lp.x = if (lastExpandedX + dm.widthPixels / 2 < dm.widthPixels / 2) 0
                       else (dm.widthPixels - dp(38)).coerceAtLeast(0)
                lp.y = lastExpandedY
                lp.width = dp(38)
                lp.height = dp(38)
            } else {
                box.removeAllViews()
                box.addView(tv, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ))
                box.setPadding(dp(16), dp(11), dp(16), dp(11))
                box.background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(0xF01A1C26.toInt())
                    setStroke(dp(1), 0x50FFFFFF.toInt())
                }
                lp.x = lastExpandedX
                lp.width = (resources.displayMetrics.widthPixels * 0.86f).toInt()
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            }
            // 部分 ROM 拒绝跨宽度部分更新；remove+add 是稳妥路径
            runCatching {
                wm?.removeView(box)
                wm?.addView(box, lp)
            }.onFailure {
                runCatching { wm?.updateViewLayout(box, lp) }
            }
        }

        box.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startLpX = lp.x; startLpY = lp.y; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    lp.x = startLpX + dx
                    lp.y = startLpY - dy
                    runCatching { wm?.updateViewLayout(box, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) applyCollapsed(!collapsed)
                    else if (collapsed) applyCollapsed(false)  // 拖动 tab 重新展开
                    true
                }
                else -> false
            }
        }

        wm?.addView(box, lp)
    }

    private fun dm0(): android.util.DisplayMetrics = resources.displayMetrics

    private fun refresh() {
        main.post {
            val text = SubtitleBridge.text
            // 折叠态：tab 常驻可见；展开态：有文本才显示（cue 间隙整窗隐藏）
            val show = if (SubtitleBridge.folded) SubtitleBridge.visible
                       else SubtitleBridge.visible && !text.isNullOrEmpty()
            container?.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
            if (!text.isNullOrEmpty()) textView?.text = text
        }
    }

    override fun onDestroy() {
        SubtitleBridge.onChanged = null
        container?.let { runCatching { wm?.removeView(it) } }
        container = null
        super.onDestroy()
    }
}
