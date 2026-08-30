package com.autostudy.helper

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * 详细设置：AI接口 / TTS朗读 / 话筒位置 / 节奏 / 蒙题兜底 / 数据管理。
 * 所有输入即改即存。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var etBaseUrl: EditText
    private lateinit var etModel: EditText
    private lateinit var etKey: EditText
    private lateinit var tvLlmTest: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvMic: TextView
    private lateinit var etRestMin: EditText
    private lateinit var etRestMax: EditText
    private lateinit var etTapGap: EditText
    private lateinit var spGuessSingle: Spinner
    private lateinit var spGuessMulti: Spinner
    private lateinit var etFailRetry: EditText
    private lateinit var tvStats: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        LogRepo.init(this)
        QuestionStore.init(this)

        val ctx = this
        val scroll = ScrollView(ctx)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(30))
        }
        scroll.addView(box, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setContentView(scroll)

        fun head(t: String): TextView = TextView(ctx).apply {
            text = t
            textSize = 16f
            setTextColor(0xFFB35A1F.toInt())
            setPadding(0, dp(16), 0, dp(6))
        }

        fun label(t: String): TextView = TextView(ctx).apply {
            text = t
            textSize = 12f
            setTextColor(0xFF777777.toInt())
            setPadding(0, dp(4), 0, dp(2))
        }

        fun edit(hint: String, text: String, password: Boolean = false): EditText =
            EditText(ctx).apply {
                this.hint = hint
                setText(text, TextView.BufferType.EDITABLE)
                textSize = 13f
                if (password) {
                    transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
                }
                setSingleLine(true)
            }

        fun btn(t: String, onClick: () -> Unit): Button = Button(ctx).apply {
            text = t
            textSize = 13f
            setOnClickListener { onClick() }
        }

        // ---------- 标题 ----------
        box.addView(TextView(ctx).apply {
            text = "详细设置"
            textSize = 22f
            setTextColor(0xFF222222.toInt())
        })

        // ---------- AI ----------
        box.addView(head("AI 答题接口（OpenAI兼容）"))
        val presets = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        presets.addView(btn("智谱GLM") {
            etBaseUrl.setText("https://open.bigmodel.cn/api/paas/v4")
            etModel.setText("glm-4-flash")
        })
        presets.addView(btn("DeepSeek") {
            etBaseUrl.setText("https://api.deepseek.com")
            etModel.setText("deepseek-chat")
        })
        presets.addView(btn("Kimi") {
            etBaseUrl.setText("https://api.moonshot.cn/v1")
            etModel.setText("moonshot-v1-8k")
        })
        box.addView(presets)
        box.addView(label("服务器地址(BaseURL，一般以 /v1 或 /v4 结尾)"))
        etBaseUrl = edit("https://open.bigmodel.cn/api/paas/v4", Prefs.llmBaseUrl)
        box.addView(etBaseUrl)
        box.addView(label("模型名称"))
        etModel = edit("glm-4-flash", Prefs.llmModel)
        box.addView(etModel)
        box.addView(label("API Key"))
        etKey = edit("在此粘贴APIKey", Prefs.llmApiKey, password = true)
        box.addView(etKey)
        tvLlmTest = TextView(ctx).apply {
            textSize = 12f
            setPadding(0, dp(4), 0, 0)
            setTextColor(0xFF888888.toInt())
        }
        box.addView(btn("保存并测试连接") {
            saveLlm()
            tvLlmTest.text = "测试中…"
            Thread {
                val err = LlmClient(LlmConfig(Prefs.llmBaseUrl, Prefs.llmApiKey, Prefs.llmModel)).test()
                runOnUiThread {
                    if (err == null) {
                        tvLlmTest.text = "✔ 连接成功"
                        tvLlmTest.setTextColor(0xFF2E7D32.toInt())
                    } else {
                        tvLlmTest.text = "✘ $err"
                        tvLlmTest.setTextColor(0xFFC62828.toInt())
                    }
                }
            }.start()
        })
        box.addView(tvLlmTest)
        box.addView(label("提示：免费推荐智谱 glm-4-flash；DeepSeek 便宜量大；本地 Ollama 填 http://127.0.0.1:11434/v1"))

        // ---------- TTS ----------
        box.addView(head("朗读语音 (TTS)"))
        tvSpeed = TextView(ctx).apply {
            textSize = 13f
            text = "朗读语速：x${Prefs.ttsSpeed}"
        }
        box.addView(tvSpeed)
        val sb = SeekBar(ctx).apply {
            max = 120 // 0.8 ~ 2.0
            progress = ((Prefs.ttsSpeed - 0.8f) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = 0.8f + p / 100f
                    tvSpeed.text = "朗读语速：x${"%.1f".format(v)}"
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    val v = 0.8f + (s?.progress ?: 50) / 100f
                    Prefs.ttsSpeed = v
                }
            })
        }
        box.addView(sb)
        box.addView(btn("测试朗读") {
            val t = TtsPlayer(ctx)
            t.start(Prefs.ttsEngine, Prefs.ttsSpeed)
            Thread {
                if (t.awaitReady(8000)) {
                    t.speakAndWait("您好，这是朗读测试。当前语速每分钟约两百字。")
                }
                t.shutdown()
            }.start()
            Toast.makeText(ctx, "开始朗读测试", Toast.LENGTH_SHORT).show()
        })
        box.addView(label("若朗读无声：请检查系统「文字转语音/朗读」输出引擎是否为中文引擎（华为手机为 华为语音引擎）"))

        // ---------- 话筒位置 ----------
        box.addView(head("小话筒按钮位置"))
        box.addView(android.widget.CheckBox(ctx).apply {
            text = "自动识别话筒位置（推荐，识别失败时用比例坐标兜底）"
            textSize = 13f
            isChecked = Prefs.micAutoDetect
            setOnCheckedChangeListener { _, c -> Prefs.micAutoDetect = c }
        })
        tvMic = TextView(ctx).apply {
            textSize = 13f
            text = "位置：X=${"%.0f".format(Prefs.micXPercent * 100)}%  Y=${"%.1f".format(Prefs.micYPercent * 100)}%"
        }
        box.addView(tvMic)
        box.addView(label("X 横向位置(屏幕宽度百分比)"))
        val sbX = SeekBar(ctx).apply {
            max = 60 // 20~80
            progress = ((Prefs.micXPercent - 0.2f) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    tvMic.text = "位置：X=${p + 20}%  Y=${"%.1f".format(Prefs.micYPercent * 100)}%"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    Prefs.micXPercent = ((s?.progress ?: 30) + 20) / 100f
                }
            })
        }
        box.addView(sbX)
        box.addView(label("Y 纵向位置(屏幕高度百分比，默认89.5%)"))
        val sbY = SeekBar(ctx).apply {
            max = 180 // 80.0~98.0 步进0.1
            progress = ((Prefs.micYPercent - 0.8f) * 1000).toInt().coerceIn(0, 180)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    tvMic.text = "位置：X=${"%.0f".format(Prefs.micXPercent * 100)}%  Y=${80 + p / 10f}%"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    Prefs.micYPercent = 0.8f + (s?.progress ?: 95) / 1000f
                }
            })
        }
        box.addView(sbY)
        box.addView(btn("在此位置试点一下(请先停留在学习页)") {
            val svc = AutoService.instance
            if (svc == null) {
                Toast.makeText(ctx, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
            } else {
                val p = svc.micPoint()
                if (p != null) svc.tap(p.x, p.y)
                Toast.makeText(ctx, "已点击", Toast.LENGTH_SHORT).show()
            }
        })

        // ---------- 节奏 ----------
        box.addView(head("运行节奏"))
        val pace = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        etRestMin = edit("休息最小秒", Prefs.restMinSec.toString())
        etRestMax = edit("休息最大秒", Prefs.restMaxSec.toString())
        etRestMin.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        etRestMax.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        pace.addView(etRestMin); pace.addView(etRestMax)
        box.addView(pace)
        box.addView(label("每个专题完成后随机休息的秒数范围"))
        etTapGap = edit("点击间隔毫秒", Prefs.tapGapMs.toString())
        box.addView(etTapGap)

        // ---------- 兜底 ----------
        box.addView(head("蒙题兜底(无AI/未命中时)"))
        val gRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        spGuessSingle = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                listOf("单选:固定选A", "单选:随机选"))
            setSelection(if (Prefs.guessSingle == "random") 1 else 0)
        }
        spGuessMulti = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                listOf("多选:全部选上", "多选:随机选"))
            setSelection(if (Prefs.guessMulti == "random") 1 else 0)
        }
        gRow.addView(spGuessSingle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        gRow.addView(spGuessMulti, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(gRow)
        box.addView(label("小测不合格时的自动重试次数"))
        etFailRetry = edit("默认2", Prefs.failRetry.toString())
        box.addView(etFailRetry)

        // ---------- 数据 ----------
        box.addView(head("数据管理"))
        tvStats = TextView(ctx).apply {
            textSize = 12f
            setTextColor(0xFF555555.toInt())
        }
        refreshStats()
        box.addView(tvStats)
        val dataRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        dataRow.addView(btn("导出题库") {
            val f = QuestionStore.export(ctx)
            Toast.makeText(ctx, if (f != null) "已导出: ${f.absolutePath}" else "导出失败", Toast.LENGTH_LONG).show()
        })
        dataRow.addView(btn("分享日志") {
            val text = LogRepo.snapshot().takeLast(300).joinToString("\n")
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "学习通关助手日志")
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(i, "分享日志"))
        })
        dataRow.addView(btn("清空题库") {
            AlertDialog.Builder(ctx)
                .setTitle("清空题库")
                .setMessage("确定清空本地缓存的所有题目答案？")
                .setPositiveButton("清空") { _, _ -> QuestionStore.clear(); refreshStats() }
                .setNegativeButton("取消", null)
                .show()
        })
        box.addView(dataRow)
        box.addView(label("战绩文件 results.csv 与日志位于 Android/data/com.autostudy.helper/files/ 下，可连电脑导出"))
        box.addView(btn("查看战绩记录") {
            val f = java.io.File(getExternalFilesDir(null), "export/results.csv")
            val content = if (f.exists()) f.readText().takeLast(3000) else "暂无战绩记录"
            AlertDialog.Builder(ctx).setTitle("战绩").setMessage(content)
                .setPositiveButton("关闭", null).show()
        })

        // 保存按钮
        box.addView(btn("保存全部设置", this::saveAll).apply {
            setPadding(0, dp(16), 0, 0)
        })
    }

    override fun onPause() {
        super.onPause()
        saveAll()
    }

    private fun saveLlm() {
        Prefs.llmBaseUrl = etBaseUrl.text.toString()
        Prefs.llmModel = etModel.text.toString()
        Prefs.llmApiKey = etKey.text.toString()
    }

    private fun saveAll() {
        saveLlm()
        Prefs.restMinSec = etRestMin.text.toString().toIntOrNull() ?: 8
        Prefs.restMaxSec = etRestMax.text.toString().toIntOrNull() ?: 25
        Prefs.tapGapMs = etTapGap.text.toString().toIntOrNull() ?: 700
        Prefs.guessSingle = if (spGuessSingle.selectedItemPosition == 1) "random" else "first"
        Prefs.guessMulti = if (spGuessMulti.selectedItemPosition == 1) "random" else "all"
        Prefs.failRetry = etFailRetry.text.toString().toIntOrNull() ?: 2
        if (Prefs.restMaxSec < Prefs.restMinSec) Prefs.restMaxSec = Prefs.restMinSec + 5
        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
    }

    private fun refreshStats() {
        tvStats.text = "累计完成 ${Prefs.totalCompleted} · 失败 ${Prefs.totalFailed} · 跳过 ${Prefs.totalSkipped} · 题库缓存 ${QuestionStore.size()} 条"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
