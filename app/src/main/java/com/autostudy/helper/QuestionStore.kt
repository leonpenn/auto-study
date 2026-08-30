package com.autostudy.helper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 本地题目缓存：AI 答过的题存下来，同一题不再重复请求。
 * 文件位置 files/qbank.json，可导出到外部目录查看/分享。
 */
object QuestionStore {

    data class Entry(
        val stem: String,
        val type: String,      // 单选 / 多选
        val answer: String,    // 如 "ACD"
        val source: String,    // llm / guess
        val ts: Long
    )

    private val map = LinkedHashMap<String, Entry>()
    private var file: File? = null

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, "qbank.json")
        load()
    }

    private fun load() {
        val f = file ?: return
        try {
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val e = Entry(
                    stem = o.getString("stem"),
                    type = o.optString("type", ""),
                    answer = o.getString("answer"),
                    source = o.optString("source", "llm"),
                    ts = o.optLong("ts", 0L)
                )
                map[norm(e.stem)] = e
            }
            LogRepo.log("qbank", "加载题库 ${map.size} 条")
        } catch (e: Exception) {
            LogRepo.log("qbank", "题库加载失败: ${e.message}")
        }
    }

    @Synchronized
    private fun save() {
        val f = file ?: return
        try {
            val arr = JSONArray()
            map.values.forEach { e ->
                arr.put(JSONObject()
                    .put("stem", e.stem)
                    .put("type", e.type)
                    .put("answer", e.answer)
                    .put("source", e.source)
                    .put("ts", e.ts))
            }
            f.writeText(arr.toString(1))
        } catch (e: Exception) {
            LogRepo.log("qbank", "题库保存失败: ${e.message}")
        }
    }

    /** 题干归一化：去掉题型标签、序号、空白和标点，用于匹配。 */
    fun norm(stem: String): String =
        stem.replace(Regex("【.*?】"), "")
            .replace(Regex("\\d+、"), "")
            .replace(Regex("[\\s。，：:，,.、“”‘’()（）\"']"), "")
            .trim()

    @Synchronized
    fun get(stem: String): Entry? = map[norm(stem)]

    @Synchronized
    fun put(stem: String, type: String, answer: String, source: String) {
        map[norm(stem)] = Entry(stem, type, answer, source, System.currentTimeMillis())
        save()
    }

    @Synchronized
    fun size(): Int = map.size

    @Synchronized
    fun all(): List<Entry> = map.values.toList()

    @Synchronized
    fun clear() {
        map.clear()
        save()
    }

    /** 导出到外部可见目录，返回文件路径，失败返回null。 */
    fun export(ctx: Context): File? {
        return try {
            val dir = File(ctx.getExternalFilesDir(null), "export")
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "qbank-${System.currentTimeMillis()}.json")
            val arr = JSONArray()
            map.values.forEach { e ->
                arr.put(JSONObject()
                    .put("stem", e.stem)
                    .put("type", e.type)
                    .put("answer", e.answer)
                    .put("source", e.source))
            }
            f.writeText(arr.toString(1))
            f
        } catch (e: Exception) {
            LogRepo.log("qbank", "导出失败: ${e.message}")
            null
        }
    }
}
