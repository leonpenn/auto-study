package com.autostudy.helper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 无障碍服务入口：负责手势派发、屏幕快照、悬浮窗与引擎的衔接。
 * 长期存活，无需前台服务。
 */
class AutoService : AccessibilityService(), Engine.EngineHost {

    companion object {
        @Volatile var instance: AutoService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private var engine: Engine? = null
    private var panel: FloatPanel? = null

    // 手势完成信号（长按模式等待用）
    private var gestureLatch = CountDownLatch(1)

    override fun onServiceConnected() {
        super.onServiceConnected()
        Prefs.init(this)
        LogRepo.init(this)
        instance = this
        engine = Engine(this, applicationContext)
        panel = FloatPanel(this, engine!!)
        LogRepo.log("svc", "无障碍服务已连接")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        LogRepo.log("svc", "无障碍服务断开")
        engine?.stop()
        main.post { panel?.remove() }
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // 引擎采用主动轮询，这里无需处理事件
    }

    fun notifyPanelStatus(status: String) {
        main.post { panel?.setStatus(status) }
    }

    fun notifyPanelDetail(detail: String) {
        main.post { panel?.setDetail(detail) }
    }

    fun showPanel() {
        main.post { panel?.show() }
    }

    fun hidePanel() {
        main.post { panel?.remove() }
    }

    // ---------------- EngineHost 实现 ----------------

    private fun usableRoot(r: AccessibilityNodeInfo?): Boolean = try {
        r != null && r.childCount > 0
    } catch (_: Exception) {
        false
    }

    private fun pkgOf(r: AccessibilityNodeInfo?): String? = try {
        r?.packageName?.toString()
    } catch (_: Exception) {
        null
    }

    /**
     * 屏幕根节点获取。关键规则：绝不能返回自己悬浮窗的窗口——
     * 部分机型在用户点击悬浮窗后会把"活动窗口"标记为悬浮窗本身，
     * 引擎就会只读到面板文字（"运行中/暂停…"），表现为永远"等待页面"。
     * 优先级：
     *  1) 活动窗口有内容且不是本软件（微信页面或系统弹窗）
     *  2) 窗口列表里的微信窗口（活动窗口被悬浮窗抢占/为空时的兜底）
     */
    override fun root(): AccessibilityNodeInfo? {
        val ourPkg = "com.autostudy.helper"
        val active = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        }
        if (usableRoot(active) && pkgOf(active) != ourPkg) {
            return active
        }
        return try {
            windows.firstOrNull { w ->
                val r = try {
                    w.root
                } catch (_: Exception) {
                    null
                }
                usableRoot(r) && pkgOf(r) == "com.tencent.mm"
            }?.root
        } catch (_: Exception) {
            null
        }
    }

    override fun tap(x: Float, y: Float): Boolean = dispatchStroke(x, y, x, y, 80)

    override fun longPress(x: Float, y: Float, ms: Long): Boolean = dispatchStroke(x, y, x, y, ms)

    override fun hold(x: Float, y: Float, ms: Long): Boolean {
        gestureLatch = CountDownLatch(1)
        val ok = dispatchStroke(x, y, x, y, ms)
        LogRepo.log("svc", "长按开始 ok=$ok ${ms}ms")
        return ok
    }

    override fun waitForGesture(timeoutMs: Long): Boolean =
        gestureLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    private fun dispatchStroke(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
        return try {
            val p = Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }
            val stroke = GestureDescription.StrokeDescription(p, 0, ms.coerceIn(40, 59000))
            val desc = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(desc, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    gestureLatch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    gestureLatch.countDown()
                }
            }, null)
        } catch (e: Exception) {
            LogRepo.log("svc", "手势异常: ${e.message}")
            false
        }
    }

    override fun back(): Boolean = try {
        performGlobalAction(GLOBAL_ACTION_BACK)
    } catch (_: Exception) {
        false
    }

    override fun swipeUp(): Boolean {
        val r = screenRect()
        return dispatchStroke(r.centerX().toFloat(), (r.top + r.height() * 0.72).toFloat(),
            r.centerX().toFloat(), (r.top + r.height() * 0.38).toFloat(), 320)
    }

    override fun swipeDown(): Boolean {
        val r = screenRect()
        // 手指向下滑 = 内容向上滚回顶部
        return dispatchStroke(r.centerX().toFloat(), (r.top + r.height() * 0.40).toFloat(),
            r.centerX().toFloat(), (r.top + r.height() * 0.78).toFloat(), 350)
    }

    override fun micPoint(): PointF? {
        // 1) 智能识别：关键词 / 底部大号圆形按钮的几何启发式
        if (Prefs.micAutoDetect) {
            findMicOnScreen()?.let { return it }
            LogRepo.log("mic", "智能识别未命中，回退比例坐标")
        }
        // 2) 比例兜底：按设置里的百分比（默认底部居中 X50% Y89.5%）
        val r = screenRect()
        if (r.isEmpty) return null
        return PointF(
            r.left + r.width() * Prefs.micXPercent,
            r.top + r.height() * Prefs.micYPercent
        )
    }

    private var lastMemoX = -1f
    private var lastMemoY = -1f

    override fun rememberNextBtn(x: Float, y: Float) {
        val r = screenRect()
        if (r.isEmpty || r.width() <= 0 || r.height() <= 0) return
        Prefs.nextBtnXPercent = (x - r.left) / r.width()
        Prefs.nextBtnYPercent = (y - r.top) / r.height()
        // 位置没变化就不重复记日志
        if (Math.abs(Prefs.nextBtnXPercent - lastMemoX) < 0.005 &&
            Math.abs(Prefs.nextBtnYPercent - lastMemoY) < 0.005
        ) return
        lastMemoX = Prefs.nextBtnXPercent
        lastMemoY = Prefs.nextBtnYPercent
        LogRepo.log(
            "svc",
            "记忆[下一题]位置: x=${"%.1f".format(Prefs.nextBtnXPercent * 100)}% y=${"%.1f".format(Prefs.nextBtnYPercent * 100)}%"
        )
    }

    override fun nextBtnPoint(): PointF? {
        if (Prefs.nextBtnXPercent <= 0f || Prefs.nextBtnYPercent <= 0f) return null
        val r = screenRect()
        if (r.isEmpty) return null
        return PointF(
            r.left + r.width() * Prefs.nextBtnXPercent,
            r.top + r.height() * Prefs.nextBtnYPercent
        )
    }

    /**
     * 自动定位小话筒按钮：
     * a) 无障碍节点描述/文字含"话筒/录音/朗读/mic" → 直接采用
     * b) 几何启发式：屏幕底部75%以下、无文字、40~110dp见方、宽高接近（圆形）、
     *    取离"底部居中(50%, 89.5%)"最近的候选
     */
    private fun findMicOnScreen(): PointF? {
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return null
        val win = Rect()
        root.getBoundsInScreen(win)
        if (win.isEmpty) return null
        val nodes = ScreenReader.collect(root, includeAll = true)
        if (nodes.isEmpty()) return null
        // 底部区域（75%以下）。关键词匹配也必须限定在此区域，
        // 否则会命中页面中部的"请朗读以下文字"等文案
        val bandTop = win.top + (win.height() * 0.75).toInt()

        // a) 关键词命中
        nodes.firstOrNull { n ->
            n.visible && n.bounds.top >= bandTop && (n.desc + n.text).let {
                it.contains("话筒") || it.contains("录音") || it.lowercase().contains("mic")
            }
        }?.let {
            LogRepo.log("mic", "识别话筒(关键词): ${it.bounds}")
            return PointF(it.bounds.centerX().toFloat(), it.bounds.centerY().toFloat())
        }

        // b) 几何启发式
        val dpi = resources.displayMetrics.density
        val minSize = (40 * dpi).toInt()
        val maxSize = (110 * dpi).toInt()
        val cands = nodes.filter { n ->
            n.visible && n.text.isEmpty() &&
                    n.bounds.top >= bandTop &&
                    n.bounds.width() in minSize..maxSize &&
                    n.bounds.height() in minSize..maxSize &&
                    // 宽高比 0.7~1.43，接近圆形
                    n.bounds.width() * 10 >= n.bounds.height() * 7 &&
                    n.bounds.height() * 10 >= n.bounds.width() * 7
        }
        if (cands.isEmpty()) return null
        val targetX = win.centerX().toFloat()
        val targetY = win.top + win.height() * 0.895f
        val best = cands.minByOrNull { n ->
            Math.abs(n.bounds.centerX() - targetX) + Math.abs(n.bounds.centerY() - targetY) * 0.5f
        } ?: return null
        LogRepo.log("mic", "识别话筒(几何): ${best.bounds} 候选数=${cands.size}")
        return PointF(best.bounds.centerX().toFloat(), best.bounds.centerY().toFloat())
    }

    override fun onStatus(status: String) {
        LogRepo.log("engine", "状态: $status")
        notifyPanelStatus(status)
    }

    /** 刷新无障碍服务配置：偶尔能唤醒"读得到窗口但内容为空"的失效状态 */
    override fun poke() {
        try {
            val info = serviceInfo
            if (info != null) setServiceInfo(info)
            LogRepo.log("svc", "已刷新无障碍服务配置")
        } catch (e: Exception) {
            LogRepo.log("svc", "刷新服务配置失败: ${e.message}")
        }
    }

    override fun onDetail(detail: String) {
        notifyPanelDetail(detail)
    }

    override fun vibrate(ms: Long) {
        try {
            val v = getSystemService(Vibrator::class.java) ?: return
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms)
            }
        } catch (_: Exception) {
        }
    }

    private fun screenRect(): Rect {
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        }
        if (root != null) {
            val r = Rect()
            root.getBoundsInScreen(r)
            if (!r.isEmpty) return r
        }
        val metrics = resources.displayMetrics
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }
}
