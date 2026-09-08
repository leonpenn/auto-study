package com.autostudy.helper

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val timeoutSec: Long = 40L
)

/**
 * OpenAI 兼容接口客户端（chat/completions）。
 * 兼容智谱 / DeepSeek / Kimi / 通义 / OpenAI / Ollama 等任何 OpenAI 格式端点。
 */
class LlmClient(private val cfg: LlmConfig) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(cfg.timeoutSec, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** 拼接端点：用户可能填到 /chat/completions 为止，避免重复拼接 */
    private fun endpoint(): String {
        val b = cfg.baseUrl.trim().trimEnd('/')
        return if (b.endsWith("/chat/completions")) b else "$b/chat/completions"
    }

    private fun systemPrompt(isMulti: Boolean): String =
        "你是银行保险话术学习小测的答题助手。根据给定的题目和选项，给出正确答案。" +
                (if (isMulti) {
                    "注意：这是多选题，正确答案有2个及以上选项，必须全部选出，" +
                            "绝不能只给1个选项。"
                } else {
                    "注意：这是单选题，只选择1个最正确的选项。"
                }) +
                "严格只输出一个JSON对象：" +
                (if (isMulti) {
                    "格式：{\"answer\":\"ACD\"}（多选，字母按顺序排列）。"
                } else {
                    "格式：{\"answer\":\"A\"}。"
                }) +
                "不要输出任何解释、markdown代码块或其他内容。"

    private fun userPrompt(
        stem: String,
        options: List<Pair<String, String>>,
        isMulti: Boolean
    ): String = buildString {
        if (isMulti) appendLine("（多选题，正确答案有2个及以上选项，必须全部选出）")
        appendLine("题目：${stem}")
        options.forEach { (letter, text) -> appendLine("$letter、$text") }
        appendLine("请只输出答案JSON。")
    }

    /**
     * 请求答案。返回形如 "AC" 的大写字母串；失败返回 null（调用方走蒙题兜底）。
     */
    fun ask(stem: String, options: List<Pair<String, String>>, isMulti: Boolean): String? {
        val body = JSONObject().apply {
            put("model", cfg.model)
            put("temperature", 0.1)
            put("max_tokens", 100)
            put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemPrompt(isMulti)))
                .put(JSONObject().put("role", "user").put("content", userPrompt(stem, options, isMulti))))
        }
        repeat(2) { attempt ->
            try {
                val req = Request.Builder()
                    .url(endpoint())
                    .header("Authorization", "Bearer ${cfg.apiKey}")
                    .header("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(jsonType))
                    .build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        LogRepo.log("llm", "HTTP ${resp.code} attempt=$attempt resp=${text.take(200)}")
                        return@use
                    }
                    val content = parseContent(text)
                    val answer = extractLetters(content)
                    if (answer != null) {
                        LogRepo.log("llm", "答案=$answer (${cfg.model})")
                        return answer
                    }
                    LogRepo.log("llm", "无法解析答案 content=${content.take(120)}")
                }
            } catch (e: Exception) {
                LogRepo.log("llm", "请求异常 attempt=$attempt: ${e.javaClass.simpleName} ${e.message}")
            }
            try {
                Thread.sleep(900)
            } catch (_: InterruptedException) {
                return null
            }
        }
        return null
    }

    private fun parseContent(respText: String): String = try {
        val root = JSONObject(respText)
        root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    } catch (e: Exception) {
        // 有些网关直接返回文本
        respText
    }

    /**
     * 从模型输出中提取答案字母。
     * 必须精确解析，绝不能对全文粗暴扫字母：提示词要求输出 {"answer":"B"}，
     * 键名 "answer" 本身含字母 A，粗暴扫描会把答案 B 污染成 "AB"。
     */
    private fun extractLetters(content: String): String? {
        val clean = content.replace("```", "").trim()
        // 1) 整体就是一个JSON对象
        try {
            val o = JSONObject(clean)
            val a = o.optString("answer", "")
            if (a.isNotEmpty()) return onlyLetters(a)
        } catch (_: Exception) {
        }
        // 2) 文本中嵌着 {"answer":"AC"} 片段
        Regex("\\{\\s*\"answer\"\\s*:\\s*\"([A-Ha-h\\s,、]+)\"\\s*\\}").find(clean)?.let {
            return onlyLetters(it.groupValues[1])
        }
        // 3) 裸答案文本（如 "AC"、"答案是AC"）
        return onlyLetters(clean)
    }

    private fun onlyLetters(s: String): String? {
        // 排序去重：多选答案与顺序无关（"BA"≡"AB"），统一为升序，
        // 保证与错题规避表(wrongTried)的精确匹配可靠
        val letters = Regex("[A-H]").findAll(s.uppercase()).map { it.value }.distinct().sorted().toList()
        return if (letters.isEmpty()) null else letters.joinToString("")
    }

    /** 设置页"测试连接"。返回 null 表示成功，否则返回错误摘要。 */
    fun test(): String? {
        return try {
            val body = JSONObject().apply {
                put("model", cfg.model)
                put("max_tokens", 20)
                put("messages", JSONArray()
                    .put(JSONObject().put("role", "user").put("content", "回复：OK")))
            }
            val req = Request.Builder()
                .url(endpoint())
                .header("Authorization", "Bearer ${cfg.apiKey}")
                .post(body.toString().toRequestBody(jsonType))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful) null
                else "HTTP ${resp.code}: ${text.take(160)}"
            }
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
