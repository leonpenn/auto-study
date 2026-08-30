package com.autostudy.helper

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 悬浮控制面板：屏幕右侧竖排小按钮（≡/状态/▶/⏹/＋ 从上往下），
 * 收起时仅约60dp宽，不遮挡标题、标签与话筒，也减少误触（误触会把
 * "活动窗口"抢给悬浮窗，导致引擎读不到微信页面）。
 * 使用 TYPE_ACCESSIBILITY_OVERLAY，无需悬浮窗权限；窗口自带亮屏保持。
 */
class FloatPanel(private val svc: AutoService, private val engine: Engine) {

    private val wm = svc.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var statusView: TextView? = null
    private var detailView: TextView? = null
    private var logView: TextView? = null
    private var startBtn: Button? = null
    private var expanded = false
    private var shown = false

    private val params = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        format = PixelFormat.TRANSLUCENT
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        // 右侧垂直居中
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        x = 6
        y = 0
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (shown) return
        val ctx = svc

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = roundBg(0xE6202124.toInt(), 14f)
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }

        // 拖动手柄（点按=展开/收起）
        val handle = TextView(ctx).apply {
            text = "≡"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }

        // 状态（小字，最多4行）
        val status = TextView(ctx).apply {
            text = "待开始"
            setTextColor(Color.WHITE)
            textSize = 9f
            width = dp(58)
            maxLines = 4
            gravity = Gravity.CENTER
        }

        fun miniBtn(text: String, onClick: () -> Unit): Button = Button(ctx).apply {
            this.text = text
            textSize = 15f
            minWidth = 0
            minimumWidth = 0
            setPadding(0, dp(4), 0, dp(4))
            setOnClickListener { onClick() }
        }

        val start = miniBtn("▶") { toggleStart() }
        val stop = miniBtn("⏹") {
            engine.stop()
            start.text = "▶"
            setStatus("已停止")
            refreshLog()
        }
        val expand = miniBtn("＋") { toggleExpand() }

        // 展开区（详情/日志/设置/隐藏），收起时隐藏
        val detail = TextView(ctx).apply {
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 10f
            setPadding(dp(4), dp(4), dp(4), 0)
            visibility = View.GONE
        }
        val log = TextView(ctx).apply {
            setTextColor(0xFF99E699.toInt())
            textSize = 9f
            setPadding(dp(4), dp(2), dp(4), 0)
            visibility = View.GONE
        }
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(4), dp(6), dp(4), 0)
        }
        val settings = Button(ctx).apply {
            text = "设置"
            textSize = 11f
            minWidth = 0
            setPadding(dp(10), dp(2), dp(10), dp(2))
            setOnClickListener {
                val it = android.content.Intent(svc, SettingsActivity::class.java)
                it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                svc.startActivity(it)
            }
        }
        val hide = Button(ctx).apply {
            text = "隐藏"
            textSize = 11f
            minWidth = 0
            setPadding(dp(10), dp(2), dp(10), dp(2))
            setOnClickListener { remove() }
        }
        btnRow.addView(settings)
        btnRow.addView(hide)

        panel.addView(handle)
        panel.addView(status)
        panel.addView(start)
        panel.addView(stop)
        panel.addView(expand)
        panel.addView(detail)
        panel.addView(log)
        panel.addView(btnRow)

        root = panel
        statusView = status
        detailView = detail
        logView = log
        startBtn = start

        // 拖动（右缘锚点，x为距右边距离，左右拖动同样有效）
        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (Math.abs(dx) > 6 || Math.abs(dy) > 6) moved = true
                    params.x = Math.max(0, startX - dx) // END锚点：向左拖增大x
                    params.y = Math.max(0, startY + dy)
                    runCatching { wm.updateViewLayout(panel, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleExpand()
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(panel, params)
            shown = true
            LogRepo.log("panel", "悬浮窗已显示(右侧竖排)")
        } catch (e: Exception) {
            LogRepo.log("panel", "悬浮窗失败: ${e.message}")
        }
    }

    private fun toggleStart() {
        val start = startBtn ?: return
        if (!engine.running || engine.paused) {
            engine.start()
            start.text = "⏸"
            setStatus("运行中")
            // 开始运行时自动收起展开区，减少遮挡
            if (expanded) toggleExpand()
        } else {
            engine.pause()
            start.text = "▶"
            setStatus("已暂停")
        }
        refreshLog()
    }

    private fun toggleExpand() {
        expanded = !expanded
        detailView?.visibility = if (expanded) View.VISIBLE else View.GONE
        logView?.visibility = if (expanded) View.VISIBLE else View.GONE
        (root?.getChildAt(root!!.childCount - 1))?.visibility =
            if (expanded) View.VISIBLE else View.GONE
        if (expanded) refreshLog()
        root?.let { runCatching { wm.updateViewLayout(it, params) } }
    }

    fun setStatus(s: String) {
        statusView?.text = s
        if (expanded) refreshLog()
    }

    fun setDetail(s: String) {
        detailView?.text = s
    }

    private fun refreshLog() {
        val lines = LogRepo.snapshot().takeLast(4).joinToString("\n") { it.substring(6) }
        logView?.text = lines
    }

    fun remove() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        shown = false
    }

    private fun roundBg(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = radius }

    private fun dp(v: Int): Int = (v * svc.resources.displayMetrics.density).toInt()
}
