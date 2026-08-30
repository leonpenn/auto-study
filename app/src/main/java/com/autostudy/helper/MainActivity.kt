package com.autostudy.helper

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * 主界面：前置条件检查清单 + 入口。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvAccess: TextView
    private lateinit var tvTts: TextView
    private lateinit var tvLlm: TextView
    private lateinit var tvBattery: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        LogRepo.init(this)
        QuestionStore.init(this)

        val ctx = this
        val root = ScrollView(ctx)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        root.addView(box, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setContentView(root)

        fun title(t: String, big: Boolean = false) = TextView(ctx).apply {
            text = t
            textSize = if (big) 22f else 15f
            setTextColor(0xFF222222.toInt())
            setPadding(0, dp(10), 0, dp(6))
        }

        fun row(label: String, initial: String, buttonText: String, color: Int,
                onBtn: () -> Unit): TextView {
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // 右侧留出悬浮窗位置（右侧竖排面板约60dp宽），避免遮挡按钮
                setPadding(0, dp(6), dp(72), dp(6))
            }
            val tv = TextView(ctx).apply {
                text = initial
                textSize = 14f
                setTextColor(color)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val b = Button(ctx).apply {
                text = buttonText
                textSize = 12f
                minWidth = 0
                setPadding(dp(12), dp(4), dp(12), dp(4))
                setOnClickListener { onBtn() }
            }
            line.addView(tv)
            line.addView(b)
            box.addView(line)
            return tv
        }

        box.addView(title("学习通关助手", big = true))
        box.addView(TextView(ctx).apply {
            text = "自动完成「话术通关」学习+小测任务\n无需Root · 数据仅存本机"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
        })

        box.addView(title("一、前置检查"))
        tvAccess = row("① 无障碍服务", "", "去开启", 0xFFC62828.toInt()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        // ①的无障碍开启路径提示
        box.addView(TextView(ctx).apply {
            text = "　└ 路径：设置→辅助功能→无障碍→已下载的服务/已安装的服务 → 开启本软件权限"
            textSize = 11f
            setTextColor(0xFF999999.toInt())
            setPadding(dp(14), 0, 0, dp(2))
        })
        tvBattery = row("② 电池优化", "", "去设置", 0xFFC62828.toInt()) {
            try {
                startActivity(Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        // ③初始必须有文字，否则整行空白只剩按钮
        tvTts = row("③ 朗读语音(TTS)：未检测", "③ 朗读语音(TTS)：未检测", "检测", 0xFF888888.toInt()) {
            tvTts.text = "③ 朗读语音(TTS)：检测中…"
            tvTts.setTextColor(0xFF888888.toInt())
            TtsPlayer.probe(ctx, Prefs.ttsEngine) { ok, name, msg ->
                runOnUiThread {
                    tvTts.text = "③ TTS: ${name.take(24)} ${if (ok) "✔" else "✘"} $msg"
                    tvTts.setTextColor(if (ok) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
                }
            }
        }
        tvLlm = row("④ AI答题接口", "", "去配置", 0xFFC62828.toInt()) {
            startActivity(Intent(ctx, SettingsActivity::class.java))
        }

        box.addView(title("二、开始使用"))
        val steps = TextView(ctx).apply {
            textSize = 13f
            setTextColor(0xFF444444.toInt())
            text = "1. 完成上面 ①~④ 检查\n" +
                    "2. 打开微信进入「话术通关」页面\n" +
                    "3. 屏幕右侧出现悬浮窗，点「▶ 开始」\n" +
                    "4. 保持亮屏前台，期间不要触碰手机\n" +
                    "5. 可随时「⏸ 暂停」或「⏹ 停止」"
        }
        box.addView(steps)
        box.addView(TextView(ctx).apply {
            textSize = 13f
            setTextColor(0xFFC62828.toInt())
            setTypeface(typeface, Typeface.BOLD)
            text = "如果点击开始按钮之后，等待一会儿没有反应，请杀掉app，重新进入，按照1~3的步骤重新启用。"
        })

        val btnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0) }
        btnRow.addView(Button(ctx).apply {
            text = "打开微信"
            textSize = 13f
            setOnClickListener {
                val i = packageManager.getLaunchIntentForPackage("com.tencent.mm")
                if (i != null) startActivity(i)
                else Toast.makeText(ctx, "未安装微信", Toast.LENGTH_SHORT).show()
            }
        })
        btnRow.addView(Button(ctx).apply {
            text = "详细设置"
            textSize = 13f
            setOnClickListener { startActivity(Intent(ctx, SettingsActivity::class.java)) }
        })
        btnRow.addView(Button(ctx).apply {
            text = "使用说明"
            textSize = 13f
            setOnClickListener { showHelp() }
        })
        box.addView(btnRow)

        box.addView(title("三、运行须知"))
        box.addView(TextView(ctx).apply {
            textSize = 11f
            setTextColor(0xFF999999.toInt())
            text = "· 首次运行建议只跑1-2个专题，观察无误后再放量\n" +
                    "· 小测答案由AI给出，答错会自动重试，仍不合格则跳过并记录\n" +
                    "· 卡住的专题会自动退出并跳过，不影响后续任务\n" +
                    "· 本工具仅操作您本人账号，请自行评估平台规则风险"
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val acc = accessibilityEnabled()
        tvAccess.text = "① 无障碍服务 ${if (acc) "✔ 已开启" else "✘ 未开启"}"
        tvAccess.setTextColor(if (acc) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())

        val battery = (getSystemService(POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)
        tvBattery.text = "② 电池优化 ${if (battery) "✔ 已忽略" else "✘ 未忽略(建议设置)"}"
        tvBattery.setTextColor(if (battery) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())

        tvLlm.text = if (Prefs.llmReady) {
            "④ AI接口 ✔ ${Prefs.llmModel} 已配置"
        } else {
            "④ AI接口 ✘ 未配置Key(将进入纯蒙题模式)"
        }
        tvLlm.setTextColor(if (Prefs.llmReady) 0xFF2E7D32.toInt() else 0xFFEF6C00.toInt())

        if (acc && AutoService.instance != null) {
            AutoService.instance?.showPanel()
        }
    }

    private fun accessibilityEnabled(): Boolean {
        val s = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return s.contains("$packageName/")
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle("使用说明")
            .setMessage(
                "【首次使用】\n" +
                        "1) 开启无障碍服务：设置→辅助功能→无障碍→已安装的服务→学习通关助手→开启\n" +
                        "2) 「详细设置」里配置AI接口(地址/模型/Key)，点测试确认连通\n" +
                        "3) 调整朗读语速(建议1.2~1.4)与话筒位置(默认屏幕下方居中)\n\n" +
                        "【每次运行】\n" +
                        "1) 打开微信→进入话术通关小程序列表页\n" +
                        "2) 点悬浮窗「▶ 开始」，App自动循环刷未通关专题\n" +
                        "3) 首次录音时微信申请麦克风权限，App会自动点允许\n" +
                        "4) 全部完成会震动提示；中途可暂停/停止\n\n" +
                        "【建议】手机插电、横竖屏保持不变、勿遮挡屏幕。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
