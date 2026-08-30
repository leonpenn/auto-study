package com.autostudy.helper

import android.content.Context
import android.content.SharedPreferences

/**
 * 全局配置存取。所有可调参数集中在这里，设置页直接读写。
 */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("autostudy", Context.MODE_PRIVATE)
    }

    // ---------- 大模型 ----------
    var llmBaseUrl: String
        get() = sp.getString("llm_base_url", "https://open.bigmodel.cn/api/paas/v4") ?: ""
        set(v) = sp.edit().putString("llm_base_url", v.trim().trimEnd('/')).apply()

    var llmApiKey: String
        get() = sp.getString("llm_api_key", "") ?: ""
        set(v) = sp.edit().putString("llm_api_key", v.trim()).apply()

    var llmModel: String
        get() = sp.getString("llm_model", "glm-4-flash") ?: ""
        set(v) = sp.edit().putString("llm_model", v.trim()).apply()

    val llmReady: Boolean get() = llmApiKey.isNotEmpty() && llmBaseUrl.isNotEmpty() && llmModel.isNotEmpty()

    // ---------- TTS ----------
    /** 朗读语速 0.8 ~ 2.0 */
    var ttsSpeed: Float
        get() = sp.getFloat("tts_speed", 1.3f)
        set(v) = sp.edit().putFloat("tts_speed", v).apply()

    /** TTS 引擎包名，空 = 系统默认 */
    var ttsEngine: String
        get() = sp.getString("tts_engine", "") ?: ""
        set(v) = sp.edit().putString("tts_engine", v.trim()).apply()

    // ---------- 话筒位置（相对屏幕比例） ----------
    /** 自动识别话筒按钮（关键词+几何启发式），失败时回退比例坐标 */
    var micAutoDetect: Boolean
        get() = sp.getBoolean("mic_auto", true)
        set(v) = sp.edit().putBoolean("mic_auto", v).apply()

    var micXPercent: Float
        get() = sp.getFloat("mic_x", 0.5f)
        set(v) = sp.edit().putFloat("mic_x", v).apply()

    var micYPercent: Float
        get() = sp.getFloat("mic_y", 0.895f)
        set(v) = sp.edit().putFloat("mic_y", v).apply()

    // ---------- "下一题/提交"按钮记忆位置（相对窗口比例，话筒模式同理） ----------
    var nextBtnXPercent: Float
        get() = sp.getFloat("next_x", 0f)
        set(v) = sp.edit().putFloat("next_x", v).apply()

    var nextBtnYPercent: Float
        get() = sp.getFloat("next_y", 0f)
        set(v) = sp.edit().putFloat("next_y", v).apply()

    // ---------- 节奏 ----------
    /** 专题之间随机休息的最小/最大秒数 */
    var restMinSec: Int
        get() = sp.getInt("rest_min", 8)
        set(v) = sp.edit().putInt("rest_min", v.coerceAtLeast(0)).apply()

    var restMaxSec: Int
        get() = sp.getInt("rest_max", 25)
        set(v) = sp.edit().putInt("rest_max", v.coerceAtLeast(1)).apply()

    /** 基础点击间隔毫秒（实际会加随机抖动） */
    var tapGapMs: Int
        get() = sp.getInt("tap_gap", 700)
        set(v) = sp.edit().putInt("tap_gap", v.coerceAtLeast(200)).apply()

    // ---------- 蒙题兜底 ----------
    /** 单选蒙法: first=固定选A / random=随机 */
    var guessSingle: String
        get() = sp.getString("guess_single", "first") ?: "first"
        set(v) = sp.edit().putString("guess_single", v).apply()

    /** 多选蒙法: all=全选 / random=随机 */
    var guessMulti: String
        get() = sp.getString("guess_multi", "all") ?: "all"
        set(v) = sp.edit().putString("guess_multi", v).apply()

    /** 小测不合格时自动"重新通关"的最大重试次数 */
    var failRetry: Int
        get() = sp.getInt("fail_retry", 2)
        set(v) = sp.edit().putInt("fail_retry", v.coerceIn(0, 10)).apply()

    // ---------- 统计 ----------
    var totalCompleted: Int
        get() = sp.getInt("total_completed", 0)
        set(v) = sp.edit().putInt("total_completed", v).apply()

    var totalFailed: Int
        get() = sp.getInt("total_failed", 0)
        set(v) = sp.edit().putInt("total_failed", v).apply()

    var totalSkipped: Int
        get() = sp.getInt("total_skipped", 0)
        set(v) = sp.edit().putInt("total_skipped", v).apply()
}
