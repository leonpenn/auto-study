package com.autostudy.helper

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * TTS 朗读器：学习页"请朗读以下文字"的真发声来源。
 * 用系统 TTS（华为为"华为语音引擎"）把话术文本读出来，外放给麦克风录音。
 */
class TtsPlayer(private val ctx: Context) {

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private val initLatch = CountDownLatch(1)
    /** 当前朗读的等待闩锁，stop() 时释放让 speakAndWait 立即返回 */
    @Volatile private var speakLatch: CountDownLatch? = null
    var engineName: String = ""
        private set

    fun start(enginePackage: String, speed: Float) {
        val listener = TextToSpeech.OnInitListener { status ->
            ready = (status == TextToSpeech.SUCCESS)
            if (ready) {
                try {
                    tts?.language = Locale.SIMPLIFIED_CHINESE
                } catch (_: Exception) {
                }
            }
            LogRepo.log("tts", "初始化完成 ok=$ready engine=${tts?.defaultEngine ?: "?"}")
            initLatch.countDown()
        }
        tts = if (enginePackage.isBlank()) {
            TextToSpeech(ctx.applicationContext, listener)
        } else {
            try {
                TextToSpeech(ctx.applicationContext, listener, enginePackage)
            } catch (e: Exception) {
                LogRepo.log("tts", "指定引擎失败，回退默认: ${e.message}")
                TextToSpeech(ctx.applicationContext, listener)
            }
        }
        setSpeed(speed)
    }

    fun awaitReady(timeoutMs: Long = 10000): Boolean {
        initLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return ready
    }

    fun isReady(): Boolean = ready

    fun setSpeed(speed: Float) {
        try {
            tts?.setSpeechRate(speed)
        } catch (_: Exception) {
        }
    }

    /**
     * 朗读并阻塞直到读完（或超时）。
     * 超时按字数放宽估算：语速1.3时中文约5字/秒，这里按2字/秒兜底，保证不会提前中断。
     */
    fun speakAndWait(text: String): Boolean {
        val t = tts ?: return false
        if (!ready) return false
        val clean = text.replace(Regex("[\\n\\r]+"), "。")
        val done = CountDownLatch(1)
        speakLatch = done
        val id = "utt-${System.nanoTime()}"
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == id) done.countDown()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == id) done.countDown()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == id) done.countDown()
            }
        })
        val okStart = try {
            t.speak(clean, TextToSpeech.QUEUE_FLUSH, null, id)
        } catch (e: Exception) {
            LogRepo.log("tts", "speak异常: ${e.message}")
            TextToSpeech.ERROR
        }
        if (okStart == TextToSpeech.ERROR) return false
        val boundSec = 20L + clean.length / 2L
        val finished = done.await(boundSec, TimeUnit.SECONDS)
        LogRepo.log("tts", "朗读结束 finished=$finished len=${clean.length}")
        return finished
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        // 释放 speakAndWait 的等待，让引擎线程立即继续（否则stop后要等朗读超时）
        speakLatch?.countDown()
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ready = false
    }

    companion object {
        /** 检测指定（或默认）引擎是否可用、是否支持中文，供设置页用。 */
        fun probe(ctx: Context, enginePackage: String, onResult: (ok: Boolean, engine: String, msg: String) -> Unit) {
            val t = if (enginePackage.isBlank()) {
                TextToSpeech(ctx.applicationContext) { }
            } else {
                try {
                    TextToSpeech(ctx.applicationContext, { }, enginePackage)
                } catch (e: Exception) {
                    onResult(false, enginePackage, "无法加载引擎: ${e.message}")
                    return
                }
            }
            t.setOnUtteranceProgressListener(null)
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.postDelayed({
                var ok = false
                var msg: String
                try {
                    val set = t.setLanguage(Locale.SIMPLIFIED_CHINESE)
                    ok = set >= 0 // LANG 或 LANG_AVAILABLE
                    msg = if (ok) "支持中文" else "不支持中文(set=$set)"
                } catch (e: Exception) {
                    msg = "检测异常: ${e.message}"
                }
                val name = t.defaultEngine ?: enginePackage
                try {
                    t.shutdown()
                } catch (_: Exception) {
                }
                onResult(ok, name, msg)
            }, 2500)
        }
    }
}
