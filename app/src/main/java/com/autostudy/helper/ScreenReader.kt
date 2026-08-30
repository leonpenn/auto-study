package com.autostudy.helper

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍树节点的轻量快照。微信小程序页面是 WebView 渲染，
 * 文本节点大多不可直接点击，所以保留 bounds 用于坐标兜底点击。
 */
data class SNode(
    val text: String,
    val desc: String,
    val cls: String,
    val clickable: Boolean,
    val enabled: Boolean,
    val bounds: Rect,
    val node: AccessibilityNodeInfo?
) {
    val display: String get() = if (text.isNotEmpty()) text else desc
    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()
    val visible: Boolean get() = !bounds.isEmpty && bounds.width() > 0 && bounds.height() > 0
}

object ScreenReader {

    /**
     * 遍历整棵无障碍树（迭代，防爆栈），最多采集 5000 个节点。
     * includeAll=true 时连纯图片/无文字节点也收集（用于话筒按钮的几何识别）。
     */
    fun collect(root: AccessibilityNodeInfo?, includeAll: Boolean = false): List<SNode> {
        if (root == null) return emptyList()
        val out = ArrayList<SNode>(256)
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 6000) {
            val n = stack.removeLast()
            try {
                val r = Rect()
                n.getBoundsInScreen(r)
                val t = n.text?.toString()?.trim().orEmpty()
                val d = n.contentDescription?.toString()?.trim().orEmpty()
                if (includeAll || t.isNotEmpty() || d.isNotEmpty() || n.isClickable) {
                    out.add(SNode(t, d, n.className?.toString().orEmpty(), n.isClickable, n.isEnabled, r, n))
                }
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { stack.addLast(it) }
                }
            } catch (_: Exception) {
                // 节点可能已失效，跳过
            }
        }
        return out
    }

    fun texts(nodes: List<SNode>): List<SNode> = nodes.filter { it.text.isNotEmpty() }

    fun findByPrefix(nodes: List<SNode>, vararg prefixes: String): SNode? =
        nodes.firstOrNull { n -> n.text.isNotEmpty() && prefixes.any { n.text.startsWith(it) } }

    fun findByContains(nodes: List<SNode>, vararg keys: String): SNode? =
        nodes.firstOrNull { n -> n.text.isNotEmpty() && keys.any { n.text.contains(it) } }

    /** 找到所有 text 满足条件的节点 */
    fun findAll(nodes: List<SNode>, predicate: (String) -> Boolean): List<SNode> =
        nodes.filter { it.text.isNotEmpty() && predicate(it.text) }

    /**
     * 调试用：把整棵树 dump 成可读文本，排查页面结构时导出。
     */
    fun dump(root: AccessibilityNodeInfo?): String {
        if (root == null) return "<null root>"
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 30) return
            val r = Rect()
            n.getBoundsInScreen(r)
            val t = n.text?.toString()?.trim().orEmpty()
            val d = n.contentDescription?.toString()?.trim().orEmpty()
            sb.append("  ".repeat(depth))
                .append(n.className?.toString()?.substringAfterLast('.') ?: "?")
                .append(" [").append(r).append("]")
                .append(if (n.isClickable) " CLICK" else "")
                .append(if (n.isEnabled) "" else " DISABLED")
                .append(if (t.isNotEmpty()) " text=\"$t\"" else "")
                .append(if (d.isNotEmpty()) " desc=\"$d\"" else "")
                .append('\n')
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { walk(it, depth + 1) }
            }
        }
        walk(root, 0)
        return sb.toString()
    }
}
