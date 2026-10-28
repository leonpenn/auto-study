package com.autostudy.helper

import android.graphics.PointF
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自动化引擎：以阻塞循环 + 轮询屏幕快照的方式驱动整个通关流程。
 *
 * 流程（对应需求截图）：
 *  00 通关列表 → 点"未通关"标签 → 点第一张专题卡片
 *  01 本次通关说明 → 点"立即开始"
 *  02/03 学习页 → 点小话筒 → TTS朗读全文 → 停止 → "下一题"/最后一条"提交"
 *  04 小测页 → 题库缓存/AI/蒙题 → 选项 → "下一题"/"提交"
 *  05 通关反馈 → 确保星级 → "提交"
 *  06 通关结果 → 记录成绩 → "返回通关列表" → 随机休息 → 下一个专题
 */
class Engine(private val host: EngineHost, private val appCtx: android.content.Context) {

    interface EngineHost {
        fun root(): AccessibilityNodeInfo?
        fun tap(x: Float, y: Float): Boolean
        fun longPress(x: Float, y: Float, ms: Long): Boolean
        /** 按住不放（异步），用于长按录音模式，与TTS并行 */
        fun hold(x: Float, y: Float, ms: Long): Boolean
        fun waitForGesture(timeoutMs: Long): Boolean
        fun back(): Boolean
        fun swipeUp(): Boolean
        fun swipeDown(): Boolean
        /** 刷新无障碍服务配置，尝试唤醒失效的屏幕读取 */
        fun poke()
        /** 话筒按钮的屏幕坐标（按设置的比例换算） */
        fun micPoint(): PointF?
        /** 记住"下一题/提交"按钮位置（识别到一次后，树塌缩时按记忆盲点） */
        fun rememberNextBtn(x: Float, y: Float)
        /** 已记忆的"下一题/提交"位置；从未识别过返回null */
        fun nextBtnPoint(): PointF?
        fun onStatus(status: String)
        fun onDetail(detail: String)
        fun vibrate(ms: Long)
        /** 当前前台窗口的包名（用于区分学习App内/外） */
        fun windowPackage(): String?
    }

    // ---------- 运行控制 ----------
    @Volatile var running = false; private set
    @Volatile var paused = false
    @Volatile private var stopFlag = false
    @Volatile private var generation = 0
    /** 引擎自动重启计数（用户手动停止时清零；连续重启超限则真停，防止崩溃风暴） */
    @Volatile private var autoRestarts = 0
    private var thread: Thread? = null
    private val rng: kotlin.random.Random = kotlin.random.Random

    // ---------- 组件 ----------
    private var tts: TtsPlayer? = null
    private var llm: LlmClient? = null
    /** AI接口是否已处于"连续失败降级"状态（防止重复告警） */
    @Volatile private var llmDegraded = false

    // ---------- 状态 ----------
    private var state: String? = null
    private var stateEnter = 0L
    private var listTabClicked = false
    private var listScrolled = false
    private var listScrolledAttempts = 0
    private var topicTitle = ""
    /** 会话内是否进入过学习任务（未知页提醒/恢复判定用；escapeTopic不清除） */
    private var hadTask = false
    private var quizRetries = 0
    private var micMode = 0 // 0=未知 1=点按 2=长按
    private var micModeTriedHold = false
    private var lastUnknownSig = ""
    private var unknownSince = 0L
    private var unknownBackCount = 0
    private val skipTitles = LinkedHashSet<String>()
    private val wrongTried = HashMap<String, MutableSet<String>>()
    private val topicAnswers = ArrayList<Pair<String, String>>() // (normStem, answer)
    private var learningFailStreak = 0
    private var emptyTreeTicks = 0
    private var stubTicks = 0
    private var stubRetries = 0
    private var lastDumpKey = ""
    private var lastDumpAt = 0L
    private var itemFailSig: String? = null    // 连续推进失败的题（签名），用于限制同一题的重读轮数
    private var itemFailCount = 0
    private var resultCounted = false          // 当前专题的成绩是否已计入统计（防返回列表点击失败时重复累加）
    private var passedAwaitingAdvance: String? = null  // 已评测通过但尚未翻页成功的题（绝不重读，只补点翻页）
    private var passAdvanceFails = 0
    /** 本专题及格线：旧平台60分，新平台（小邮助手）从详情页"不低于NN分"提取 */
    private var passScore = 60
    private var lastLearnedSig: String? = null   // 刚读完并已点下一题的那条（防页面未切换时重复朗读）
    private var lastLearnX: String? = null       // 学习进度x，前进时刷新看门狗
    private var lastQuizStem: String? = null     // 当前小测题干，切题时刷新看门狗
    private var quizSubmitSig: String? = null    // 已点过"提交"的题干（防重复提交；重试/换题时复位）
    private var quizSubmitWaits = 0              // 提交后等待跳转的轮数（8轮上限后允许重新提交）
    private val answeredStems = LinkedHashSet<String>() // 本专题已作答过的题
    /** 本专题朗读原文（小测AI答题参考材料；专题通过/跳过/换专题后清除） */
    private val topicReadings = ArrayList<String>()
    private val topicReadingsCap = 12000         // 材料总字数上限（防极端长专题撑爆请求）
    /** 新平台（小邮助手）是独立App：运行中动态记录其包名，用于退出后自动重新拉起 */
    @Volatile private var appPkg: String? = null

    // ---------- 统计 ----------
    @Volatile var statCompleted = 0; private set
    @Volatile var statFailed = 0; private set
    @Volatile var statSkipped = 0; private set
    @Volatile var statLlm = 0; private set
    @Volatile var statGuess = 0; private set
    @Volatile var remaining = -1; private set

    // ================= 生命周期 =================

    @Synchronized
    fun start() {
        if (running) {
            val alive = try {
                thread?.isAlive == true
            } catch (_: Exception) {
                false
            }
            if (alive) {
                paused = false
                host.onStatus("继续运行")
                return
            }
            // 僵尸状态：running未复位但线程已死（点开始无反应的根源），强制重启
            LogRepo.log("engine", "检测到引擎线程已死，强制重启")
            running = false
        }
        generation++
        val g = generation
        stopFlag = false
        paused = false
        running = true
        autoRestarts = 0
        QuestionStore.init(appCtx)
        thread = Thread {
            loop(g)
        }.apply {
            name = "engine-loop"
            start()
        }
    }

    fun pause() {
        paused = true
        host.onStatus("已暂停")
    }

    fun stop() {
        stopFlag = true
        paused = false
        tts?.stop()
    }

    private fun loop(g: Int) {
        LogRepo.log("engine", "引擎启动 gen=$g")
        // 本地引用：退出时只关自己的TTS，避免极端时序下误关新一轮循环的TTS
        val player = TtsPlayer(appCtx).also { it.start(Prefs.ttsEngine, Prefs.ttsSpeed) }
        try {
            tts = player
            val ttsOk = player.awaitReady(12000)
            LogRepo.log("engine", "TTS就绪=$ttsOk")
            if (!ttsOk) host.onStatus("⚠ TTS语音引擎不可用，无法朗读")
            if (ttsOk) {
                // 预热：首次合成常有延迟，先空读一词，避免第一段录音只录到半截；
                // 同时验证引擎真能开口（部分引擎声明不支持中文但实测可朗读）
                player.speakAndWait("预热")
                val started = player.lastStartOk()
                LogRepo.log("engine", "预热朗读 started=$started")
                if (!started) {
                    host.onStatus("⚠ TTS预热未开口，建议安装第三方语音引擎")
                    host.vibrate(600)
                }
            }
            if (Prefs.llmReady) {
                llm = LlmClient(LlmConfig(Prefs.llmBaseUrl, Prefs.llmApiKey, Prefs.llmModel))
                llmDegraded = false
            }
            if (running && g == generation) host.onStatus("运行中")

            var lastBeat = System.currentTimeMillis()
            while (!stopFlag && g == generation) {
                try {
                    if (paused) {
                        Thread.sleep(500)
                        continue
                    }
                    step()
                    // 心跳：每30秒记录引擎与屏幕读取状态，任何静默卡住都能从日志定位
                    val now = System.currentTimeMillis()
                    if (now - lastBeat >= 30_000) {
                        lastBeat = now
                        val rootInfo = try {
                            val r = host.root()
                            if (r == null) "root=null"
                            else {
                                val n = ScreenReader.collect(r).size
                                val p = try {
                                    r.packageName?.toString()
                                } catch (_: Exception) {
                                    "?"
                                }
                                "root=${n}节点 pkg=$p"
                            }
                        } catch (t: Throwable) {
                            "root异常:${t.javaClass.simpleName}"
                        }
                        LogRepo.log("engine", "心跳: state=$state paused=$paused $rootInfo")
                    }
                } catch (t: Throwable) {
                    LogRepo.log("engine", "step异常: ${t.javaClass.simpleName}: ${t.message}")
                    sleep(2000)
                }
            }
        } catch (t: Throwable) {
            LogRepo.log("engine", "引擎线程异常终止: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            player.shutdown()
            if (g == generation) {
                running = false
                // 非用户主动停止：自动以新代数重启（上限5次，防崩溃风暴）。
                // 22.jpg案例：长按阻塞期间抛Error级异常跳过防护直出finally，引擎死亡但任务未完成
                if (!stopFlag && autoRestarts < 5) {
                    autoRestarts++
                    LogRepo.log("engine", "引擎异常退出，自动重启(${autoRestarts}/5)")
                    host.onStatus("⚠ 引擎异常，自动重启(${autoRestarts}/5)")
                    host.vibrate(300)
                    Thread {
                        try {
                            Thread.sleep(1500)
                        } catch (_: InterruptedException) {
                        }
                        start()
                    }.start()
                } else {
                    if (autoRestarts >= 5) {
                        LogRepo.log("engine", "连续自动重启达上限，停止运行")
                        host.onStatus("⚠ 引擎反复异常已停止，请分享日志")
                        host.vibrate(1000)
                    }
                    host.onStatus("已停止")
                }
            }
            LogRepo.log("engine", "引擎退出 gen=$g")
        }
    }

    // ================= 主状态机 =================

    private fun step() {
        val root = host.root() ?: run { sleep(700); return }
        val nodes = ScreenReader.collect(root)
        if (nodes.isEmpty()) {
            // 屏幕内容持续不可读：给用户可见的提示而不是静默空转
            emptyTreeTicks++
            if (emptyTreeTicks == 8) {
                LogRepo.log("engine", "屏幕内容持续不可读，提示用户检查并尝试自愈")
                host.onStatus("⚠ 读不到屏幕，请重开无障碍开关或重启微信")
                host.vibrate(400)
                host.poke()
            }
            // 之后每24次tick（约17秒）再尝试一次自愈
            if (emptyTreeTicks > 8 && (emptyTreeTicks - 8) % 24 == 0) {
                host.poke()
            }
            sleep(700)
            return
        }
        if (emptyTreeTicks > 8) {
            LogRepo.log("engine", "屏幕内容已恢复可读")
            host.onStatus("运行中")
        }
        emptyTreeTicks = 0
        // 页面节点数过少：WebView无障碍桥疑似失效（偶发塌缩成十几个残骸节点），定期自愈
        if (nodes.size < 25) {
            stubTicks++
            if (stubTicks == 10) {
                LogRepo.log("engine", "页面仅${nodes.size}个节点，疑似读取失效，尝试自愈")
                host.onStatus("⚠ 页面读取不完整，自愈中")
                host.poke()
                host.vibrate(300)
            }
            if (stubTicks == 25) host.poke()
            if (stubTicks == 40) {
                // 刷新配置无效：返回键退出当前页重进（服务端有进度，重进会续上）
                stubRetries++
                if (stubRetries >= 3) {
                    escapeTopic("页面反复读取失效")
                    stubTicks = 0
                    return
                }
                LogRepo.log("engine", "自愈无效，返回键重进恢复(第${stubRetries}次)")
                host.onStatus("页面读取失效，返回重进")
                host.back()
                sleep(2200)
                try {
                    host.root()?.let { r ->
                        val nn = ScreenReader.collect(r)
                        nn.firstOrNull { it.text in setOf("确定", "确认", "退出") }?.let {
                            clickNode(it)
                            LogRepo.log("engine", "点击退出确认弹窗")
                            sleep(1200)
                        }
                    }
                } catch (_: Exception) {
                }
                stubTicks = 0
                enterState(null)
                return
            }
        } else {
            stubTicks = 0
        }
        val joined = nodes.joinToString("\n") { it.display }

        // 新平台（小邮助手）是独立App：在其页面上时动态记录包名，
        // 供"意外退出到桌面"时自动重新拉起
        if ((appPkg == null || appPkg!!.isEmpty()) &&
            (joined.contains("小邮助手") || joined.contains("通关次数") ||
                    joined.contains("题目数量（") || joined.contains("训练次数"))
        ) {
            appPkg = host.windowPackage()
            appPkg?.let { LogRepo.log("engine", "记录学习App包名: $it") }
        }

        when {
            looksLikeMicPermission(joined) -> handleMicPermission(nodes, joined)
            joined.contains("本次通关说明") -> handleDesc(nodes)
            // 新平台（小邮助手）任务详情页：训练次数/任务介绍为其特有文案，直接点"立即开始"
            joined.contains("训练次数") || joined.contains("任务介绍") -> handleDesc(nodes)
            // 反馈/结果页要先于小测判断（弹层后面能看到题目文本）
            joined.contains("通关反馈") -> handleFeedback(nodes)
            // 结果页两平台：旧"通关结果"，新平台"通关评价/题目解析"
            joined.contains("通关结果") || joined.contains("通关评价") ||
                    joined.contains("题目解析") -> handleResult(nodes, joined)
            isQuiz(joined, nodes) -> handleQuiz(nodes)
            // 学习页：读完朗读后"请朗读以下文字"可能变为"朗读完成"，用进度头兜底
            isLearning(joined) -> handleLearning(nodes, joined)
            // 新平台中间页（57.jpg）：话术通关卡片+查看按钮+场景对练——点"查看"进任务列表
            // 特征三项任一即视为模块主页（提高识别覆盖）
            joined.contains("场景对练") || joined.contains("新人专区") ||
                    joined.contains("话术库") -> handleHub(nodes)
            // 列表页：旧平台"话术通关"，新平台（小邮助手）标题+卡片"通关次数"
            joined.contains("话术通关") || joined.contains("小邮助手") ||
                    joined.contains("通关次数") -> handleList(nodes, joined)
            else -> handleUnknown(nodes, joined)
        }
        watchdog()
    }

    private val learnHeaderRe = Regex("(?:学习|题目数量)[（(](\\d+)/(\\d+)[)）]")

    private fun isLearning(joined: String): Boolean =
        joined.contains("请朗读") || learnHeaderRe.containsMatchIn(joined)

    private fun enterState(s: String?) {
        if (state != s) {
            state = s
            stateEnter = System.currentTimeMillis()
            if (s != null) unknownBackCount = 0
        }
    }

    private fun watchdog() {
        val s = state ?: return
        val elapsed = System.currentTimeMillis() - stateEnter
        val limit = when (s) {
            "LIST" -> 90_000L
            "DESC" -> 30_000L
            "LEARN" -> 480_000L
            "QUIZ" -> 300_000L
            "FEEDBACK" -> 60_000L
            "RESULT" -> 90_000L
            else -> 60_000L
        }
        if (elapsed > limit) {
            LogRepo.log("engine", "状态[$s]超时 ${elapsed / 1000}s，尝试恢复")
            if (s == "LIST") {
                listTabClicked = false
                stateEnter = System.currentTimeMillis()
            } else {
                escapeTopic("状态超时")
            }
        }
    }

    /** 卡死兜底：返回键退出专题，连续失败则把该专题加入跳过名单 */
    private fun escapeTopic(reason: String) {
        LogRepo.log("engine", "跳过专题($reason): $topicTitle")
        if (topicTitle.isNotEmpty()) {
            skipTitles.add(topicTitle)
            statSkipped++
            Prefs.totalSkipped = Prefs.totalSkipped + 1
        }
        host.back()
        sleep(2200)
        // 可能弹出"确认退出学习"弹窗
        try {
            host.root()?.let { r ->
                val nn = ScreenReader.collect(r)
                nn.firstOrNull { it.text in setOf("确定", "确认", "退出") }?.let {
                    clickNode(it)
                    LogRepo.log("engine", "点击退出确认弹窗")
                    sleep(1200)
                }
            }
        } catch (_: Exception) {
        }
        topicTitle = ""
        topicAnswers.clear()
        answeredStems.clear()
        topicReadings.clear()
        lastLearnedSig = null
        lastLearnX = null
        lastQuizStem = null
        quizSubmitSig = null
        quizSubmitWaits = 0
        passedAwaitingAdvance = null
        passAdvanceFails = 0
        quizRetries = 0
        enterState(null)
        updateDetail("恢复中")
    }

    // ================= 00 通关列表 =================

    private fun handleList(nodes: List<SNode>, joined: String) {
        enterState("LIST")
        remaining = Regex("未通关\\((\\d+)\\)").find(joined)?.groupValues?.get(1)?.toIntOrNull() ?: remaining
        updateDetail("列表：剩余未通关 $remaining")

        if (!listTabClicked) {
            val tab = ScreenReader.findByPrefix(nodes, "未通关")
            if (tab != null) {
                LogRepo.log("list", "点击[未通关]标签")
                clickNode(tab)
                sleep(1300)
            } else {
                LogRepo.log("list", "未找到[未通关]标签，直接查找卡片")
            }
            listTabClicked = true
            return
        }

        val dateRe = Regex("\\d{4}\\.\\d{2}\\.\\d{2}")
        val cards = ScreenReader.findAll(nodes) { t ->
            t.length >= 10 && (dateRe.containsMatchIn(t) || t.startsWith("【"))
        }.filter { it.visible && it.bounds.top > 200 }

        val candidates = cards.filter { c -> skipTitles.none { k -> k.isNotEmpty() && c.text.startsWith(k) } }
        // 兜底卡片：严格样式（日期/【开头）未命中时，点标签行下方最上方的"标题样"文本
        var card = candidates.minByOrNull { it.bounds.top }
        if (card == null) {
            val fb = fallbackCard(nodes)
            if (fb != null && skipTitles.none { k -> k.isNotEmpty() && fb.text.startsWith(k) }) {
                LogRepo.log("list", "严格卡片未命中，兜底选择: ${fb.text.take(30)}")
                card = fb
            }
        }

        if (card == null) {
            dumpScreenOnce("list-nocard")
            if (remaining == 0) {
                finishAll()
                return
            }
            if (listScrolledAttempts < 3) {
                // 允许连续滚动3次（加载慢/卡片靠下）；每次暂停恢复后计数清零重新尝试
                LogRepo.log("list", "当前屏未找到卡片，尝试滚动(${listScrolledAttempts + 1}/3)")
                host.swipeUp()
                listScrolledAttempts++
                listScrolled = true
                sleep(1500)
            } else {
                host.onStatus("列表无未通关卡片，暂停")
                LogRepo.log("list", "滚动3次仍无卡片，暂停等待人工处理")
                host.vibrate(600)
                paused = true
                // 复位滚动计数：用户处理完点"继续"时会重新尝试滚动3次，
                // 而不是立即再次暂停（A1修复）
                listScrolledAttempts = 0
                listScrolled = false
            }
            return
        }
        listScrolled = false
        listScrolledAttempts = 0
        topicTitle = card.text.take(50)
        hadTask = true
        // 每个新专题优先回到点按录音模式（点按历史表现最好；
        // 长按模式只在点按失败时作为本专题内的兜底）
        micMode = 1
        micModeTriedHold = false
        answeredStems.clear()
        topicReadings.clear()
        lastLearnedSig = null
        lastLearnX = null
        lastQuizStem = null
        quizSubmitSig = null
        quizSubmitWaits = 0
        resultCounted = false
        passedAwaitingAdvance = null
        passAdvanceFails = 0
        quizRetries = 0
        stubRetries = 0
        LogRepo.log("list", "进入专题: $topicTitle")
        host.onStatus("刷: ${topicTitle.take(18)}")
        clickNode(card)
        sleep(rand(1500L, 2600L))
    }

    /** 兜底找卡片：标签/搜索框下方最上方的"标题样"文本（排除元数据行） */
    private fun fallbackCard(nodes: List<SNode>): SNode? {
        // 锚点：旧平台"未通关/已通关"标签，新平台（小邮助手）搜索框
        val anchor = ScreenReader.findByPrefix(nodes, "未通关")
            ?: ScreenReader.findByPrefix(nodes, "已通关")
            ?: ScreenReader.findByContains(nodes, "输入")
            ?: return null
        if (!anchor.visible) return null
        val tabBottom = anchor.bounds.bottom
        return nodes.filter { n ->
            n.visible && n.text.length in 6..80 && n.bounds.top > tabBottom + 30 &&
                    !n.text.contains("#") &&
                    !n.text.contains("长期有效") &&
                    !n.text.contains("话术通关") &&
                    !n.text.contains("输入") &&
                    !n.text.contains("通关次数") &&
                    !n.text.contains("截止时间") &&
                    !n.text.contains("训练次数") &&
                    !n.text.contains("任务介绍") &&
                    !n.text.contains("小邮助手") &&
                    !n.text.contains("通关合格") &&
                    !Regex("\\d{4}\\.\\d{2}\\.\\d{2}").containsMatchIn(n.text) &&
                    !Regex("^[\\d\\s人+]+$").containsMatchIn(n.text)
        }.minByOrNull { it.bounds.top }
    }

    private fun finishAll() {
        host.onStatus("🎉 全部通关完成！")
        updateDetail("未通关数量为0，任务结束。共完成$statCompleted 失败$statFailed 跳过$statSkipped")
        LogRepo.log("engine", "全部未通关专题已完成")
        host.vibrate(1200)
        sleep(2000)
        paused = true
    }

    // ================= 01 通关说明弹窗 =================

    private fun handleDesc(nodes: List<SNode>) {
        enterState("DESC")
        updateDetail("通关说明弹窗")
        // 及格线自适应：新旧详情页均有"通关合格条件…不低于NN分"
        // （旧平台60分，新平台80分），提取为本专题的通过阈值
        nodes.firstOrNull { it.text.contains("不低于") && it.text.contains("分") }?.let {
            Regex("(\\d{2,3})\\s*分").find(it.text)?.groupValues?.get(1)?.toIntOrNull()
                ?.takeIf { s -> s in 60..100 }?.let { s ->
                    if (s != passScore) {
                        passScore = s
                        LogRepo.log("desc", "及格线=$passScore 分")
                    }
                }
        }
        // 新平台（小邮助手）无未通关过滤，列表混合已通关任务：
        // 详情页"训练次数 N次"N>0 = 此前已通关 → 点"稍后再来"返回列表并跳过
        if (topicTitle.isNotEmpty()) {
            val trained = nodes.firstOrNull { it.text.contains("训练次数") }?.let {
                Regex("(\\d+)").find(it.text)?.groupValues?.get(1)?.toIntOrNull()
                    ?: nodes.filter { n ->
                        n.visible && Math.abs(n.bounds.centerY() - it.bounds.centerY()) < 60 &&
                                n.bounds.left > it.bounds.right
                    }.minByOrNull { n -> n.bounds.left }?.text?.toIntOrNull()
            }
            if (trained != null && trained > 0) {
                LogRepo.log("desc", "训练次数=$trained 已通关过，跳过该任务")
                skipTitles.add(topicTitle)
                statSkipped++
                Prefs.totalSkipped = Prefs.totalSkipped + 1
                val later = ScreenReader.findByContains(nodes, "稍后再来")
                if (later != null && later.visible) {
                    clickNode(later)
                } else {
                    host.back()
                }
                sleep(rand(1500L, 2500L))
                topicTitle = ""
                topicAnswers.clear()
                topicReadings.clear()
                answeredStems.clear()
                lastLearnedSig = null
                lastLearnX = null
                lastQuizStem = null
                quizSubmitSig = null
                quizSubmitWaits = 0
                enterState(null)
                return
            }
        }
        val go = ScreenReader.findByContains(nodes, "立即开始")
            ?: ScreenReader.findByContains(nodes, "开始学习")
        if (go != null) {
            LogRepo.log("desc", "点击[立即开始]")
            clickNode(go)
            sleep(rand(1400L, 2400L))
        } else {
            sleep(1000)
        }
    }

    // ================= 02/03 学习页（朗读） =================

    private fun handleLearning(nodes: List<SNode>, joined: String) {
        enterState("LEARN")
        // 进度头两平台兼容：旧"学习(x/y)"，新"题目数量（x/y）"（全角括号）
        val progress = learnHeaderRe.find(joined)
        val x = progress?.groupValues?.get(1) ?: "?"
        // 进度前进时刷新看门狗：长专题（如7条话术）整个学习阶段都处于LEARN态，
        // 若不刷新会被状态超时误判为卡死而跳过
        if (x != lastLearnX) {
            stateEnter = System.currentTimeMillis()
            lastLearnX = x
        }
        // 页面已显示"已通过"（返回重进恢复场景）：无需重读，直接推进
        if (latestReadVerdict(nodes) == "pass") {
            LogRepo.log("learn", "页面已有已通过标识，直接推进翻页")
            stateEnter = System.currentTimeMillis()
            if (patientAdvance(30_000L)) {
                learningFailStreak = 0
                sleep(rand(900L, 1600L))
            } else {
                sleep(1200)
            }
            return
        }
        var content = pickLearningContent(nodes)
        if (content == null || ScreenReader.findByPrefix(nodes, "请朗读") == null) {
            // 重读场景：页面可能停在语音条/转写文字区域，正文卡不在可视区。
            // 先下滑滚回顶部（底部按钮和话筒固定不受影响），再重新提取
            if (scrollToReadTop()) {
                val r2 = host.root()
                if (r2 != null) {
                    val rec = pickLearningContent(ScreenReader.collect(r2))
                    if (rec != null) content = rec
                }
            }
        }
        if (content == null) {
            // 残树导致提取失败：交给step()的残树自愈流程（返回重进），不计入朗读失败
            if (nodes.size < 25) {
                LogRepo.log("learn", "页面残树无法提取正文，等待自愈")
                sleep(1500)
                return
            }
            LogRepo.log("learn", "未找到朗读正文，等待/重试")
            learningFailStreak++
            if (learningFailStreak > 6) escapeTopic("找不到朗读内容")
            sleep(1500)
            return
        }
        // 题目签名：优先用"学习(x/y)"的进度号x（稳定，不受正文提取波动影响）；
        // 页面无进度头时退回内容哈希
        val sig = if (x == "?") "c${content.hashCode()}" else "x$x"
        // 已评测通过、只差翻页：持续补点推进，绝不重读
        if (sig == passedAwaitingAdvance) {
            if (patientAdvance(30_000L)) {
                passedAwaitingAdvance = null
                passAdvanceFails = 0
                lastLearnedSig = sig
                learningFailStreak = 0
                itemFailSig = null
                itemFailCount = 0
                sleep(rand(900L, 1600L))
            } else {
                passAdvanceFails++
                // 刷新看门狗：重试期间不算卡死
                stateEnter = System.currentTimeMillis()
                if (passAdvanceFails >= 5) {
                    escapeTopic("已通过但持续无法翻页")
                } else {
                    LogRepo.log("learn", "已通过但翻页未成功，继续补点(${passAdvanceFails}/5)")
                    sleep(1200)
                }
            }
            return
        }
        if (sig == lastLearnedSig) {
            sleep(1200)
            return
        }
        updateDetail("学习 $x/${progress?.groupValues?.get(2) ?: "?"} 朗读中(${content.length}字)")

        val advanced = learnOneItemWithFallback(content, sig)
        if (advanced) {
            learningFailStreak = 0
            itemFailSig = null
            itemFailCount = 0
            // 暂存本条朗读原文，供小测阶段AI答题参考（总量超上限后不再追加）
            if (topicReadings.sumOf { it.length } < topicReadingsCap) {
                topicReadings.add(content)
            }
            // 已通过待翻页时不标记"已读"（翻页成功后才标记）
            if (passedAwaitingAdvance == null) lastLearnedSig = sig
            sleep(rand(900L, 1600L))
        } else {
            // 同一道题推进失败计数：超过2轮直接跳过该专题，绝不无限重读
            if (itemFailSig != sig) {
                itemFailSig = sig
                itemFailCount = 0
            }
            itemFailCount++
            learningFailStreak++
            LogRepo.log("learn", "本题推进失败 streak=$itemFailCount")
            if (itemFailCount >= 2) {
                escapeTopic("同一题连续${itemFailCount}轮朗读推进失败")
            } else {
                sleep(1200)
            }
        }
    }

    /** 语音条时长标记，如 31" / 5″ / 12' （朗读记录条） */
    private val voiceBarRe = Regex("^\\d{1,3}\\s*[\"”″']\\s*$")

    /** 底部话筒提示文字（"轻点一下开始跟读，无需长按"等），绝不能混进朗读内容 */
    private val readHintRe = Regex("跟读|无需长按|轻点一下|重录")

    /**
     * 滚回页面顶部，直到能看到"请朗读以下文字"标签。
     * 已在顶部时不动（避免触发下拉刷新）。底部按钮和话筒固定，不受滚动影响。
     */
    private fun scrollToReadTop(): Boolean {
        repeat(4) {
            val root = host.root() ?: return false
            val nodes = ScreenReader.collect(root)
            val label = ScreenReader.findByPrefix(nodes, "请朗读")
            if (label != null && label.visible) return true
            LogRepo.log("learn", "未见朗读标签，下滑回顶部")
            host.swipeDown()
            sleep(900)
        }
        return false
    }

    /**
     * 提取朗读正文：
     * 以"请朗读以下文字"为上边界、第一条语音条(如 31")为下边界；
     * 无语音条时下界取屏幕88%（避开底部"轻点一下开始跟读"提示与话筒）。
     * 语音条下方是语音识别转写文字，绝不能读。
     */
    private fun pickLearningContent(nodes: List<SNode>): String? {
        val uiKeys = listOf("请朗读", "上一题", "下一题", "提交", "学习(", "小测(", "话术通关", "立即开始", "通关")
        val label = ScreenReader.findByPrefix(nodes, "请朗读")
        if (label != null && label.visible) {
            val top = label.bounds.bottom - 6
            val voiceTop = nodes.filter { it.text.isNotEmpty() && voiceBarRe.containsMatchIn(it.text) }
                .map { it.bounds.top }
                .filter { it > label.bounds.top }
                .minOrNull()
            val hardBottom = screenBounds()?.let { it.top + (it.height() * 0.88).toInt() } ?: Int.MAX_VALUE
            val bottomLimit = voiceTop?.minus(4) ?: hardBottom
            val cands = nodes.filter {
                it.text.length >= 8 && it.bounds.top >= top && it.bounds.top < bottomLimit
            }.filter { n ->
                uiKeys.none { n.text.contains(it) } &&
                        !voiceBarRe.containsMatchIn(n.text) &&
                        !(n.text.length <= 25 && readHintRe.containsMatchIn(n.text))
            }
            // 正文卡可能被拆成多个文本节点，按位置拼接
            val text = cands.sortedBy { it.bounds.top * 10000 + it.bounds.left }
                .joinToString("") { it.text }
            if (text.isNotEmpty()) return text
        }
        // 兜底：最长非UI文本（排除顶部20%标题区）
        val minTop = screenBounds()?.let { it.top + (it.height() * 0.20).toInt() } ?: 0
        return nodes.filter { it.text.length >= 15 }
            .filter { n -> uiKeys.none { n.text.contains(it) } }
            .filter { it.bounds.top >= minTop }
            .maxByOrNull { it.text.length }
            ?.text
    }

    private fun screenBounds(): Rect? {
        val root = host.root() ?: return null
        val r = Rect()
        root.getBoundsInScreen(r)
        return if (r.isEmpty) null else r
    }

    private fun learnOneItemWithFallback(content: String, sig: String): Boolean {
        if (learnOneItem(content, sig)) return true
        if (micMode != 2 && !micModeTriedHold) {
            micModeTriedHold = true
            micMode = 2
            LogRepo.log("learn", "点按模式失败，切换长按录音模式重试")
            host.onStatus("已切换长按录音模式")
            return learnOneItem(content, sig)
        }
        return false
    }

    /**
     * 完成一条朗读：启动录音→TTS读全文→停止→按评测标识决定重录或推进。
     * 以"最近一次"评测标识为准（多条记录堆叠时取最下方的标识）：
     * 未通过 → 留在本页重录；已通过 → 点下一题推进。
     */
    private fun learnOneItem(content: String, sig: String): Boolean {
        var attempt = 0
        var failVerdicts = 0
        while (attempt++ < 3 && !stopFlag) {
            // 每次录音尝试都刷新看门狗，防止长流程被误判卡死
            stateEnter = System.currentTimeMillis()
            val recConfirmed = ensureRecordingAndRead(content)
            if (!recConfirmed) LogRepo.log("learn", "警告：本次未确认录音已开始")
            // 等评测标识出现（已通过/未通过），最长12秒
            val verdict = awaitReadVerdict(15_000L)
            LogRepo.log("learn", "朗读评测=${verdict ?: "无标识"} attempt=$attempt")
            if (verdict == "fail") {
                failVerdicts++
                if (failVerdicts >= 2) {
                    LogRepo.log("learn", "连续${failVerdicts}次评测未通过，放弃本题")
                    return false
                }
                LogRepo.log("learn", "评测未通过，留在本页重录")
                sleep(800)
                continue
            }
            // 已通过（或无标识）：耐心推进
            if (patientAdvance(20_000L)) {
                LogRepo.log("learn", "朗读推进成功 attempt=$attempt")
                return true
            }
            if (verdict == "pass") {
                // 已通过：绝不重读！标记后交由上层持续补点翻页
                passedAwaitingAdvance = sig
                LogRepo.log("learn", "已通过但翻页未完成，转入持续补点推进")
                return true
            }
            LogRepo.log("learn", "第${attempt}次朗读推进失败（无评测标识）")
            dumpScreenOnce("learn-fail")
            if (recordingVisible()) {
                LogRepo.log("learn", "检测到残留录音，点击停止")
                host.micPoint()?.let { host.tap(it.x, it.y) }
                sleep(900)
            }
        }
        return false
    }

    /** 最近一次朗读的评测标识："pass"/"fail"；页面无标识返回null。多条记录取最下方（最新） */
    private fun latestReadVerdict(nodes: List<SNode>): String? {
        val marks = nodes.filter { it.text.contains("已通过") || it.text.contains("未通过") }
        val latest = marks.maxByOrNull { it.bounds.top } ?: return null
        return if (latest.text.contains("未通过")) "fail" else "pass"
    }

    /** 等待评测标识出现；期间若已翻到小测/反馈/结果页，视为通过 */
    private fun awaitReadVerdict(timeoutMs: Long): String? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs && !stopFlag) {
            val root = host.root()
            if (root != null) {
                val nodes = ScreenReader.collect(root)
                val v = latestReadVerdict(nodes)
                if (v != null) return v
                val j = nodes.joinToString("\n") { it.display }
                if (j.contains("【单选】") || j.contains("【多选】") || j.contains("【判断】") ||
                    j.contains("通关反馈") || j.contains("通关结果") || j.contains("通关评价")
                ) return "pass"
            }
            // 评测结果通常前5秒就出来，轮询密一点；之后放缓
            sleep(if (System.currentTimeMillis() - start < 5000) 400 else 800)
        }
        return null
    }

    /**
     * 朗读后的推进：周期性点击"下一题/提交"，直到进度前进或进入小测/反馈/结果页。
     * 期间若出现"未通过"标识，立即停止推进（下一题此时不可用），转由上层重录。
     */
    private fun patientAdvance(maxWaitMs: Long): Boolean {
        val start = System.currentTimeMillis()
        var lastClick = 0L
        val beforeX = host.root()?.let { learningSig(ScreenReader.collect(it)) } ?: -2
        while (System.currentTimeMillis() - start < maxWaitMs && !stopFlag) {
            val root = host.root()
            if (root == null) {
                sleep(600)
                continue
            }
            val nodes = ScreenReader.collect(root)
            val j = nodes.joinToString("\n") { it.display }
            val quizOrResult = j.contains("【单选】") || j.contains("【多选】") || j.contains("【判断】") ||
                    j.contains("通关反馈") || j.contains("通关结果") || j.contains("通关评价")
            if (quizOrResult) return true
            val afterX = learningSig(nodes)
            if (afterX > beforeX) return true
            if (latestReadVerdict(nodes) == "fail") {
                LogRepo.log("learn", "推进中出现未通过标识，转重录")
                return false
            }
            // 仍在当前题：每隔3.5秒补点一次下一题/提交
            val now = System.currentTimeMillis()
            if (now - lastClick > 3500) {
                lastClick = now
                val btn = findNextButton(nodes)
                var advanced = false
                if (btn != null) {
                    // 底部导航按钮必须用真实坐标点按：小程序按钮不响应无障碍ACTION_CLICK
                    host.rememberNextBtn(btn.centerX.toFloat(), btn.centerY.toFloat())
                    host.tap(btn.centerX.toFloat(), btn.centerY.toFloat())
                    advanced = advanceConfirmWindow(beforeX)
                } else if (nodes.size < 25) {
                    // 残树（如18节点）："下一题"就在底部固定位置，按记忆位置盲点即可，
                    // 不必等树恢复——位置记忆机制正是为这一刻准备的
                    val memo = host.nextBtnPoint()
                    if (memo != null) {
                        LogRepo.log("learn", "残树(${nodes.size}节点)，按记忆位置点击下一题")
                        host.tap(memo.x, memo.y)
                        advanced = advanceConfirmWindow(beforeX)
                    } else {
                        LogRepo.log("learn", "残树且无记忆位置，尝试自愈")
                        host.poke()
                        sleep(1500)
                    }
                } else {
                    LogRepo.log("learn", "推进中未找到底部[下一题/提交]，尝试滚动")
                    host.swipeUp()
                    sleep(800)
                }
                if (advanced) return true
            }
            sleep(700)
        }
        return false
    }

    /** 朗读一条内容。返回 true=本次确认录音已开始（话筒秒数/录音指示出现） */
    private fun ensureRecordingAndRead(content: String): Boolean {
        val mic = host.micPoint() ?: run {
            LogRepo.log("learn", "无法定位话筒坐标")
            return false
        }
        val player = tts ?: return false
        if (!player.isReady()) {
            LogRepo.log("learn", "TTS未就绪，无法朗读（请检查系统文字转语音引擎）")
            return false
        }
        if (micMode == 2) {
            // 长按模式：按住话筒的同时 TTS 朗读，读完后等按压手势真正结束再返回
            // （期间绝不能派发其他手势——任何新手势都会把按住中途取消，截断录音）
            // 估算：中文TTS约每秒 4.2*语速 个字，另留4秒余量
            val estMs = (content.length * 1000f / (4.2f * Prefs.ttsSpeed.coerceAtLeast(0.5f))).toLong() + 4000
            val holdMs = estMs.coerceIn(6000, 58000)
            LogRepo.log("learn", "长按录音模式 hold=${holdMs / 1000}s (${content.length}字)")
            host.onStatus("长按录音中…")
            host.hold(mic.x, mic.y, holdMs)
            sleep(500)
            player.speakAndWait(content)
            sleep(600)
            // 阻塞等待按压结束（朗读通常已耗时大半，这里只等剩余部分）
            host.waitForGesture(holdMs + 6000)
            LogRepo.log("learn", "长按结束，等待评测")
            return true
        }
        // 点按模式：点一下开始录音
        host.tap(mic.x, mic.y)
        // 每500ms轮询录音指示，最多3秒（比固定等待更快发现启动）
        var recVisible = false
        var waited = 0
        while (waited < 3000 && !stopFlag) {
            sleep(500)
            waited += 500
            if (recordingVisible()) { recVisible = true; break }
        }
        recordingSeconds()?.let { LogRepo.log("learn", "录音已开始(${it}s)") }
        // 第一次没录上：再点一次（首次点击可能落在页面加载完成前），不再做长按试探
        if (!recVisible) {
            LogRepo.log("learn", "首次点按未见录音指示，重点一次")
            host.tap(mic.x, mic.y)
            waited = 0
            while (waited < 2000 && !stopFlag) {
                sleep(500)
                waited += 500
                if (recordingVisible()) { recVisible = true; break }
            }
        }
        if (recVisible) LogRepo.log("learn", "确认录音中，开始朗读")

        player.speakAndWait(content)
        sleep(400)
        if (recVisible) {
            // 再次点击=提交跟读
            host.tap(mic.x, mic.y)
            // 验证录音确实停止（秒数/指示消失），最多5秒；2秒后还在录就再点一次
            var w2 = 0
            var retapped = false
            while (w2 < 5000 && !stopFlag) {
                sleep(700)
                w2 += 700
                if (!recordingVisible()) break
                if (!retapped && w2 >= 2100) {
                    retapped = true
                    LogRepo.log("learn", "录音仍在进行，再点一次停止")
                    host.tap(mic.x, mic.y)
                }
            }
            LogRepo.log("learn", "停止录音完成(${w2}ms)")
        } else {
            // 两次点按都没录上：如实返回失败，交给上层切换长按模式，
            // 这里不再盲点第二次（否则可能在语音结束后又开一段空录音）
            LogRepo.log("learn", "两次点按均未检测到录音指示")
            return false
        }
        if (micMode == 0) {
            micMode = 1
            LogRepo.log("learn", "使用点按录音模式")
        }
        return recVisible
    }

    /** 录音进行中的秒数（话筒圈内的"26s"字样）；null=未在录音 */
    private fun recordingSeconds(): Int? {
        val root = host.root() ?: return null
        val sb = screenBounds() ?: return null
        val bandTop = sb.top + (sb.height() * 0.75).toInt()
        for (n in ScreenReader.collect(root)) {
            if (!n.visible || n.bounds.top < bandTop) continue
            Regex("^(\\d{1,3})\\s*[sS秒]$").find(n.text)?.let {
                return it.groupValues[1].toIntOrNull()
            }
        }
        return null
    }

    /** 录音进行中的指示：秒数计时 / "再次点击,提交跟读" / 录音相关文案 / 计时器 */
    private fun recordingVisible(): Boolean {
        if (recordingSeconds() != null) return true
        val root = host.root() ?: return false
        val nodes = ScreenReader.collect(root)
        return nodes.any { n ->
            Regex("\\d{1,2}:\\d{2}").containsMatchIn(n.text) ||
                    listOf("录音", "正在朗读", "朗读中", "松开", "停止录音", "取消", "再次点击", "提交跟读")
                        .any { n.text.contains(it) || n.desc.contains(it) }
        }
    }

    /**
     * 找底部栏的"下一题/提交"按钮。带 y 位置过滤：按钮固定在屏幕底部
     * （top > 78% 高度）。WebView 滚动后会把 fixed 按钮坐标错报成页面布局
     * 位置（y 可能变成 50% 附近的中部），严格过滤防止点到页面中部/悬浮窗。
     */
    private fun findNextButton(nodes: List<SNode>): SNode? {
        val screenH = screenBounds()?.height() ?: return null
        val bottomLimit = screenH * 0.78
        // "小测"=新平台朗读最后一题的推进按钮（53.jpg底部"小测 >"）
        val btn = ScreenReader.findByContains(nodes, "下一题")
            ?: ScreenReader.findByContains(nodes, "小测")
            ?: nodes.filter { it.text.startsWith("提交") }.minByOrNull { it.bounds.bottom }
            ?: return null
        return if (btn.visible && btn.bounds.top >= bottomLimit) btn else null
    }

    /** 点击推进按钮后的2.8秒确认窗口：只观察不补点，翻页较慢时不会误点下一题的按钮 */
    private fun advanceConfirmWindow(beforeX: Int): Boolean {
        var v = 0
        while (v < 2800 && !stopFlag) {
            sleep(600)
            v += 600
            val r3 = host.root() ?: continue
            val n3 = ScreenReader.collect(r3)
            val j3 = n3.joinToString("\n") { it.display }
            if (j3.contains("【单选】") || j3.contains("【多选】") || j3.contains("【判断】") ||
                j3.contains("通关反馈") || j3.contains("通关结果") || j3.contains("通关评价")
            ) return true
            if (learningSig(n3) > beforeX) return true
        }
        return false
    }

    /** 学习进度号x（"学习(x/y)"），无进度头返回-1 */
    private fun learningSig(nodes: List<SNode>): Int {
        val m = learnHeaderRe.find(nodes.joinToString(" ") { it.display })
        return m?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    /** 把当前屏幕的文本节点摘要写进日志（同一key只dump一次，全局限频防刷屏），远程排查用 */
    private fun dumpScreenOnce(key: String) {
        val now = System.currentTimeMillis()
        if (key == lastDumpKey) return
        if (now - lastDumpAt < 8000) return
        lastDumpKey = key
        lastDumpAt = now
        try {
            val root = host.root() ?: return
            val nodes = ScreenReader.collect(root)
            val head = nodes.filter { it.text.isNotEmpty() }
                .take(25)
                .joinToString(" | ") { it.text.take(30) }
            LogRepo.log("dump", "[$key] $head")
        } catch (_: Exception) {
        }
    }

    // ================= 04 小测 =================

    private fun isQuiz(joined: String, nodes: List<SNode>): Boolean {
        // 题型标记两平台：旧"【单选】/【多选】"，新"多选题/单选题/判断题"
        if (joined.contains("【单选】") || joined.contains("【多选】") || joined.contains("【判断】") ||
            joined.contains("多选题") || joined.contains("单选题") || joined.contains("判断题")
        ) return true
        val optCount = ScreenReader.findAll(nodes) { Regex("^[A-H]、").containsMatchIn(it) }.size
        // 旧平台兜底：小测进度+选项行（半角/全角括号都兼容）
        if (Regex("小测[（(]\\d+/\\d+[)）]").containsMatchIn(joined) && optCount > 0) return true
        // 新平台（小邮助手）：学习页无选项行，≥3个"A、"选项行即视为小测
        return optCount >= 3
    }

    private fun handleQuiz(nodes: List<SNode>) {
        enterState("QUIZ")

        val optRe = Regex("^([A-H])、\\s*(.*)$")
        val opts = nodes.mapNotNull { n ->
            optRe.matchEntire(n.text)?.let { m ->
                Triple(m.groupValues[1], m.groupValues[2].trim(), n)
            }
        }.sortedBy { it.third.bounds.top }
        if (opts.isEmpty()) {
            LogRepo.log("quiz", "未找到选项，滚动重试")
            host.swipeUp()
            sleep(1500)
            return
        }
        val availableLetters = opts.map { it.first.first() }

        // 题干：定位【单选】/【多选】标记行，收集标记行到第一个选项之间的所有文本
        // （注意不能用 contains("【") 匹配，否则会误抓页面顶部的专题标题）
        val firstOptTop = opts.minOf { it.third.bounds.top }
        val stemParts = nodes.filter { it.text.isNotEmpty() && it.bounds.bottom <= firstOptTop + 8 && it.bounds.top > 150 }
            .sortedBy { it.bounds.top * 10000 + it.bounds.left }
        val marker = stemParts.firstOrNull {
            it.text.contains("【单选】") || it.text.contains("【多选】") || it.text.contains("【判断】") ||
                    it.text.contains("多选题") || it.text.contains("单选题") || it.text.contains("判断题")
        }
        // 有标记时以标记行为题干起点；无标记时取选项上方约2行作为题干
        val lowestTop = stemParts.maxOfOrNull { it.bounds.top } ?: 0
        val bandStart = marker?.bounds?.top?.minus(10) ?: (lowestTop - 100)
        val stemText = stemParts.filter { it.bounds.top >= bandStart && it.bounds.top < firstOptTop - 6 }
            .sortedBy { it.bounds.top * 10000 + it.bounds.left }
            .joinToString("") { it.text }
        if (stemText.isEmpty()) {
            LogRepo.log("quiz", "题干解析失败，等待下一轮")
            sleep(1500)
            return
        }
        val stemKey = QuestionStore.norm(stemText)
        val isMulti = stemText.contains("多选")
        val type = if (isMulti) "多选" else "单选"
        // 题目切换时刷新看门狗，避免多题小测因AI耗时被误判卡死
        if (stemText != lastQuizStem) {
            stateEnter = System.currentTimeMillis()
            lastQuizStem = stemText
            quizSubmitWaits = 0
        }
        updateDetail("小测：${stemText.take(24)}")
        LogRepo.log("quiz", "题干=$stemText 选项=${availableLetters.joinToString("")}")

        // ---------- 决定答案：缓存 → AI → 蒙题（同一题只作答一次，防止翻页失败重复点击取消选中） ----------
        // 多选题平台要求至少选2项才能提交，单字母答案视为无效（不入缓存、不采用）
        if (stemKey in answeredStems) {
            LogRepo.log("quiz", "本题已作答过，跳过选项直接翻页")
        } else {
            var answer: String? = QuestionStore.get(stemText)?.answer
            var source = if (answer != null) "cache" else ""
            if (answer != null && isMulti && answer.length < 2) {
                LogRepo.log("quiz", "缓存多选答案仅${answer.length}个字母，视为无效")
                answer = null
                source = ""
            }
            if (answer == null && llm != null) {
                host.onStatus("AI答题中…")
                answer = llm!!.ask(stemText, opts.map { it.first to it.second }, isMulti, topicReadings.toList())
                // AI健康监控：连续失败5次（欠费/Key失效的典型特征）告警一次；
                // 恢复成功时提示并清标志，避免每次都打扰
                val c = llm!!.consecutiveFails
                if (c >= 5 && !llmDegraded) {
                    llmDegraded = true
                    val reason = llm!!.lastHttpError ?: "未知错误"
                    LogRepo.log("llm", "AI接口连续失败${c}次，已降级蒙题：$reason")
                    host.onStatus("⚠ AI接口异常已转蒙题：$reason")
                    host.vibrate(800)
                } else if (c == 0 && llmDegraded) {
                    llmDegraded = false
                    LogRepo.log("llm", "AI接口已恢复正常")
                    host.onStatus("AI接口已恢复")
                }
                if (answer != null && isMulti && answer!!.length < 2) {
                    LogRepo.log("quiz", "AI多选答案仅${answer}一个字母，视为无效转兜底")
                    answer = null
                }
                if (answer != null) {
                    QuestionStore.put(stemText, type, answer!!, "llm")
                    source = "llm"
                    statLlm++
                }
            }
            if (answer != null && answer in (wrongTried[stemKey] ?: emptySet())) {
                LogRepo.log("quiz", "AI答案${answer}上次答错过，本轮换一个")
                answer = null
            }
            if (answer == null) {
                // 重试轮优先策略（用户指定）：多选题此前答错过 → 优先全选
                // （保证提交可用；全选组合本身试过则走常规蒙题）
                val tried = wrongTried[stemKey].orEmpty()
                val allSet = availableLetters.joinToString("")
                if (isMulti && tried.isNotEmpty() && allSet !in tried) {
                    answer = allSet
                    source = "${source}retryAll".trimStart('+')
                    LogRepo.log("quiz", "多选重试轮优先全选")
                }
            }
            if (answer == null) {
                answer = guessAnswer(isMulti, availableLetters, wrongTried[stemKey].orEmpty())
                source = if (source.isEmpty()) "guess" else "${source}+guess"
                statGuess++
            }
            // 与可用选项求交集；排序去重避免重复点击取消选中
            val letters = answer.orEmpty().uppercase().filter { it in availableLetters }.toSortedSet()
            var finalLetters = if (letters.isEmpty()) guessAnswer(isMulti, availableLetters, emptySet())
            else letters.joinToString("")
            // 多选题最终防线：不足2项则全选（保证提交按钮可用）
            if (isMulti && finalLetters.length < 2) {
                LogRepo.log("quiz", "多选最终答案仅${finalLetters}一项，按全选兜底")
                finalLetters = availableLetters.joinToString("")
            }
            LogRepo.log("quiz", "作答=$finalLetters 来源=$source")

            for (letter in finalLetters) {
                val opt = opts.firstOrNull { it.first == letter.toString() } ?: continue
                clickNode(opt.third)
                sleep(rand(400L, 800L))
            }
            topicAnswers.add(stemKey to finalLetters)
            answeredStems.add(stemKey)
        }

        // ---------- 下一题 / 提交 ----------
        // 已提交过小测且尚未翻页：只等待，绝不重复点提交（A4修复，防重复提交）；
        // 但等待有上限（8轮约16秒+开销），超时清除标记允许重点提交，
        // 且状态保持QUIZ让看门狗可超时兜底（不再enterState(null)翻转复位）
        if (quizSubmitSig == stemKey) {
            quizSubmitWaits++
            if (quizSubmitWaits >= 8) {
                quizSubmitWaits = 0
                quizSubmitSig = null
                LogRepo.log("quiz", "提交后长时间未跳转，清除标记允许重新提交")
            } else {
                LogRepo.log("quiz", "本题已提交过，等待服务器跳转(${quizSubmitWaits}/8)")
                sleep(2000)
            }
            return
        }
        val btn = findNextButton(nodes)
        if (btn == null) {
            // 树内容不全时按记忆位置盲点（学习页/小测页底部栏位置相同）
            val memo = host.nextBtnPoint()
            if (memo != null) {
                LogRepo.log("quiz", "未找到底部按钮，按记忆位置点击推进")
                host.tap(memo.x, memo.y)
                sleep(rand(1500L, 2500L))
                return
            }
            LogRepo.log("quiz", "未找到[下一题/提交]，滚动")
            host.swipeUp()
            sleep(1200)
            return
        }
        host.rememberNextBtn(btn.centerX.toFloat(), btn.centerY.toFloat())
        val isSubmit = btn.text.contains("提交")
        // 底部导航按钮必须用真实坐标点按（小程序按钮不响应无障碍ACTION_CLICK）
        host.tap(btn.centerX.toFloat(), btn.centerY.toFloat())
        if (isSubmit) quizSubmitSig = stemKey
        if (isSubmit) {
            LogRepo.log("quiz", "已提交小测，等待评测结果")
            host.onStatus("小测已提交，等待结果")
            // 提交后服务器评测需要时间，耐心等待反馈/结果页出现，周期性补点提交
            val start = System.currentTimeMillis()
            var lastClick = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 15_000 && !stopFlag) {
                sleep(800)
                val r2 = host.root() ?: continue
                val n2 = ScreenReader.collect(r2)
                val j2 = n2.joinToString("\n") { it.display }
                if (j2.contains("通关结果") || j2.contains("通关反馈") || j2.contains("通关评价")) break
                // 可能出现确认弹窗
                val confirm = n2.firstOrNull { it.text in setOf("确定", "确认") }
                if (confirm != null) {
                    clickNode(confirm)
                    LogRepo.log("quiz", "点击提交确认弹窗")
                    sleep(800)
                    continue
                }
                val now = System.currentTimeMillis()
                if (now - lastClick > 4000) {
                    lastClick = now
                    // 小测提交按钮同样用真实坐标点按
                    n2.filter { it.text.startsWith("提交") }
                        .minByOrNull { it.bounds.bottom }
                        ?.let { host.tap(it.centerX.toFloat(), it.centerY.toFloat()) }
                }
            }
        }
        sleep(rand(1300L, 2200L))
        // 确认翻页
        var waited = 0
        while (waited < 5000) {
            sleep(700); waited += 700
            val r2 = host.root() ?: break
            val n2 = ScreenReader.collect(r2)
            val j2 = n2.joinToString("\n") { it.display }
            if (!j2.contains(stemText.take(10)) || j2.contains("通关反馈") || j2.contains("通关结果") || j2.contains("通关评价")) break
        }
    }

    private fun guessAnswer(isMulti: Boolean, available: List<Char>, exclude: Set<String>): String {
        // 排除本轮重试中已被证实错误的选项
        val pool = available.filter { c -> exclude.none { it.contains(c) } }
        return if (isMulti) {
            // 平台要求多选至少2项才能提交：任何分支都保证返回≥2个字母
            val wantRandom = Prefs.guessMulti == "random" || pool.size < available.size
            when {
                pool.size >= 2 && !wantRandom ->
                    // 全选（未被证伪）
                    available.joinToString("")
                pool.size >= 2 ->
                    // 从未证伪选项随机挑2~N项（"全选"已被证伪时绝不重复全选）
                    pool.shuffled(rng).take(2 + rng.nextInt(pool.size - 1))
                        .sorted().joinToString("")
                else ->
                    // 未证伪选项不足2个：从全部选项随机挑2项碰运气，
                    // 绝不重复已被证伪的完整组合
                    available.shuffled(rng).take(2).sorted().joinToString("")
            }
        } else {
            if (Prefs.guessSingle == "random") {
                (if (pool.isEmpty()) available else pool).random(rng).toString()
            } else {
                // 固定选最前面一个未被证伪的字母（A错顺延B，依次类推）
                (pool.firstOrNull() ?: available.first()).toString()
            }
        }
    }

    // ================= 04.5 新平台中间页（57.jpg） =================

    /**
     * 小邮助手中间页：返回通关列表后落在此页（话术通关卡片+查看按钮+场景对练）。
     * 点"查看"进入任务列表（50.jpg），交回 handleList 继续下一个专题。
     */
    private fun handleHub(nodes: List<SNode>) {
        enterState("HUB")
        updateDetail("话术通关中间页")
        val view = ScreenReader.findByContains(nodes, "查看")
        if (view != null && view.visible) {
            LogRepo.log("hub", "点击[查看]进入任务列表")
            clickNode(view)
            sleep(rand(1500L, 2500L))
        } else {
            sleep(1200)
        }
    }

    // ================= 05 通关反馈 =================

    /**
     * 通关反馈：**主力方法=横向扫描星级**（实测每次成功——扫描终点=第5星，
     * 且不依赖任何节点/坐标猜测），扫描后提交。
     * 补救轮：若弹层仍在，改用星星节点真实位置点击（第1轮Y带不准时的补救）；
     * 再不行用备用Y带（屏高82%）再扫描；最后尝试关闭弹层放行。
     */
    private fun handleFeedback(nodes: List<SNode>) {
        enterState("FEEDBACK")
        updateDetail("通关反馈弹窗")

        val root = host.root() ?: return
        val after = ScreenReader.collect(root, includeAll = true)
        val sheetTop = after.filter { it.text == "通关反馈" }.maxOfOrNull { it.bounds.top }
            ?: nodes.filter { it.text == "通关反馈" }.maxOfOrNull { it.bounds.top }
            ?: return

        fun pickStar(list: List<SNode>): SNode? = list.filter {
            it.visible && it.bounds.top > sheetTop + 150 &&
                    it.bounds.width() in 30..200 && it.bounds.height() in 30..200 &&
                    it.text.isEmpty()
        }.maxByOrNull { it.bounds.left }

        fun pickSubmit(list: List<SNode>): SNode? =
            list.filter { it.text == "提交" }.maxByOrNull { it.bounds.bottom }

        fun sheetGone(): Boolean {
            val r = host.root() ?: return true
            return ScreenReader.collect(r, includeAll = true).none { it.text == "通关反馈" }
        }

        /** 提交并验证；返回true=弹层已消失（成功） */
        fun submitRound(tag: String): Boolean {
            val r = host.root() ?: return false
            val cur = ScreenReader.collect(r, includeAll = true)
            val s = pickSubmit(cur)
            if (s == null) {
                LogRepo.log("feedback", "$tag：未找到提交按钮")
                return false
            }
            clickNode(s)
            sleep(1600)
            val gone = host.root()?.let {
                ScreenReader.collect(it, includeAll = true).none { n -> n.text == "通关反馈" }
            } ?: false
            LogRepo.log("feedback", "$tag：提交${if (gone) "成功" else "后弹层仍在"}")
            return gone
        }

        // 第1轮（主力，v1.0.35已验证模式）：锚点Y带横向扫描 → 立即提交
        LogRepo.log("feedback", "横向扫描星级(主力)")
        sweepStars(after)
        if (submitRound("第1轮扫描后")) {
            LogRepo.log("feedback", "反馈提交成功")
            return
        }

        // 第2轮：星星节点真实位置点击 → 提交
        val fresh = host.root()?.let { ScreenReader.collect(it, includeAll = true) } ?: return
        val star = pickStar(fresh)
        if (star != null) {
            LogRepo.log("feedback", "第2轮：星星节点点击 ${star.bounds}")
            clickNode(star)
            sleep(700)
        }
        if (submitRound("第2轮节点后")) {
            LogRepo.log("feedback", "反馈提交成功")
            return
        }

        // 第3轮：锚点扫描再来一次 → 提交
        LogRepo.log("feedback", "第3轮：再次横向扫描")
        sweepStars(fresh)
        if (submitRound("第3轮扫描后")) {
            LogRepo.log("feedback", "反馈提交成功")
            return
        }

        // 兜底：若输入框被聚焦（软键盘弹出），先按返回收起键盘恢复布局，再尝试关闭弹层
        val rLast = host.root() ?: return
        val last = ScreenReader.collect(rLast, includeAll = true)
        if (last.none { it.text == "通关反馈" }) return
        if (last.any { it.focused }) {
            LogRepo.log("feedback", "输入框聚焦中，按返回收起键盘")
            host.back()
            sleep(1000)
        }
        val rLast2 = host.root() ?: return
        val last2 = ScreenReader.collect(rLast2, includeAll = true)
        if (last2.any { it.text == "通关反馈" }) {
            LogRepo.log("feedback", "尝试关闭弹层放行")
            last2.firstOrNull {
                it.display == "×" || it.display == "✕" || it.desc.contains("关闭")
            }?.let { clickNode(it) }
            sleep(1200)
        }
    }

    /** 采集当前屏幕（含无文字图标节点） */
    private fun latestCollectInc(): List<SNode> {
        val r = host.root() ?: return emptyList()
        return ScreenReader.collect(r, includeAll = true)
    }

    /**
     * 星级行横向扫描：在"评价提示行"下方的高度带上，从屏宽10%到50%逐点点击。
     * 星级组件以最后一次点击为准——扫描覆盖真实星星行时，最右侧的点即5星。
     * 每点后若弹层消失（提交恰好生效）立即返回。
     */
    private fun sweepStars(ref: List<SNode>) {
        // Y带只允许锚点定位（评价行下方55px）。82%屏高的"备用Y带"已废弃——
        // 它与内容输入框重叠，扫描会连点输入框弹出软键盘顶乱布局（v1.0.36事故）
        val anchor = ref.firstOrNull {
            it.text.contains("请对本次通关") || it.text.contains("进行评价")
        } ?: run {
            LogRepo.log("feedback", "无评价行锚点，跳过扫描（防误点输入框）")
            return
        }
        val baseY = anchor.bounds.bottom + 55f
        val sw = screenBounds()?.width() ?: return
        // 扫描区间：屏宽 10%~52%，共 9 个候选点，从左到右（终点偏右=5星）
        val xs = (1..9).map { sw * (0.10f + 0.0525f * it) }
        for (x in xs) {
            if (stopFlag) return
            LogRepo.log("feedback", "扫描星级点击: (${x.toInt()}, ${baseY.toInt()})")
            host.tap(x, baseY)
            sleep(400)
            // 每点一次检查弹层是否意外消失（例如扫描点恰好命中了可关闭区域）
            val r = host.root() ?: continue
            val cur = ScreenReader.collect(r)
            if (cur.none { it.text == "通关反馈" }) {
                LogRepo.log("feedback", "扫描中弹层消失，评分完成")
                return
            }
        }
    }

    // ================= 06 通关结果 =================

    private fun handleResult(nodes0: List<SNode>, joined0: String) {
        enterState("RESULT")
        // 结果页刚打开时数据可能未加载完（24.jpg）：过早点"返回通关列表"会直接
        // 退出小程序或触发服务器报错。等全部加载标志齐备（最多20秒），
        // 超时则强制继续点击；期间出现"重试"弹窗自动点击
        var nodes = nodes0
        var joined = joined0
        var loadWaited = 0
        var complete = false
        while (loadWaited < 20_000 && !stopFlag) {
            val j = nodes.joinToString("\n") { it.display }
            val hasTitle = j.contains("通关排名")
            val hasBranch = j.contains("支行网点")
            val hasPassed = Regex("已通关\\(\\d+\\)").containsMatchIn(j)
            val hasUnpassed = Regex("未通关\\(\\d+\\)").containsMatchIn(j)
            // 失败结果页没有"通关排名"标题（44.jpg实证），不强等它：
            // 排名表+两个计数齐备即视为加载完成（旧平台）；
            // 新平台（小邮助手）结果页特征="通关评价"，出现即视为加载完成
            complete = j.contains("通关评价") || (hasBranch && hasPassed && hasUnpassed)
            if (complete) break
            sleep(700)
            loadWaited += 700
            val r = host.root() ?: break
            nodes = ScreenReader.collect(r)
            joined = nodes.joinToString("\n") { it.display }
        }
        LogRepo.log(
            "result",
            "结果页加载等待 ${loadWaited}ms 完整=$complete" +
                    (if (!complete) "（超时强制继续）" else "")
        )
        host.onStatus(if (complete) "结果加载完成" else "结果加载超时，强制返回")
        // 成绩节点限定在屏幕上部60%区域内取——排名列表（含他人"NN分"独立节点）
        // 位于页面下方，树序在前的排名条目可能被误当成本次成绩。
        // 新平台（55.jpg）分数在盾牌内："分数"文字节点附近找纯数字节点
        val scoreTopLimit = screenBounds()?.let { it.top + (it.height() * 0.60).toInt() } ?: Int.MAX_VALUE
        val score = nodes.filter { it.visible && it.bounds.top < scoreTopLimit }.mapNotNull {
            Regex("^\\s*(\\d{1,3})\\s*分\\s*$").find(it.text)?.groupValues?.get(1)?.toIntOrNull()
        }.firstOrNull()
            ?: run {
                // 新平台："分数"标签附近的 1~3 位纯数字节点
                val scoreLabel = nodes.firstOrNull { it.text == "分数" } ?: return@run null
                nodes.filter {
                    it.visible && it.text.matches(Regex("\\d{1,3}")) && it.bounds.top < scoreTopLimit &&
                            Math.abs(it.bounds.centerX() - scoreLabel.bounds.centerX()) < 300 &&
                            Math.abs(it.bounds.centerY() - scoreLabel.bounds.centerY()) < 200
                }.minByOrNull { Math.abs(it.bounds.centerY() - scoreLabel.bounds.centerY()) }
                    ?.text?.toIntOrNull()
            }
            ?: Regex("首次通关成绩\\s*(\\d{1,3})\\s*分").find(joined)?.groupValues?.get(1)?.toIntOrNull()
        // 通过判定（用户指定首选判据）：
        // ①页面有"返回通关列表"按钮（新旧平台通过页都有）→ 通过
        // ②旧平台失败页有"再次挑战" → 不通过
        // ③新平台失败页（通关评价但无任何按钮，56.jpg）→ 不通过
        val hasBackToList = ScreenReader.findByContains(nodes, "返回通关列表")?.takeIf { it.visible }
        val hasRetake = ScreenReader.findByContains(nodes, "再次挑战")?.takeIf { it.visible }
        val isNewResult = joined.contains("通关评价")
        val pass = when {
            hasBackToList != null -> true
            hasRetake != null -> false
            isNewResult && score != null -> score >= passScore
            isNewResult -> false
            else -> joined.contains("恭喜通关") || joined.contains("通关成功") || (score != null && score >= passScore)
        }
        updateDetail("成绩: ${score ?: "?"} ${if (pass) "✔合格" else "✘不合格"}")
        LogRepo.log("result", "专题=$topicTitle 分数=$score pass=$pass retries=$quizRetries")

        if (pass) {
            if (!resultCounted) {
                resultCounted = true
                statCompleted++
                Prefs.totalCompleted = Prefs.totalCompleted + 1
                appendResultCsv(topicTitle, score, pass, quizRetries)
            }
            if (topicTitle.isNotEmpty()) skipTitles.add(topicTitle)
            wrongTried.clear()
            topicAnswers.clear()
            answeredStems.clear()
            passedAwaitingAdvance = null
            lastLearnedSig = null
            lastLearnX = null
            lastQuizStem = null
            quizSubmitSig = null
            quizSubmitWaits = 0
            quizRetries = 0
            val back = ScreenReader.findByContains(nodes, "返回通关列表")
            if (back != null) {
                clickNode(back)
            } else {
                host.back()
            }
            sleep(rand(1600L, 2600L))
            val rest = rand(Prefs.restMinSec * 1000L, Prefs.restMaxSec.coerceAtLeast(Prefs.restMinSec + 1) * 1000L)
            host.onStatus("休息 ${rest / 1000}s")
            LogRepo.log("engine", "专题完成，休息${rest / 1000}s")
            sleep(rest)
            topicTitle = ""
            // 小测顺利通过：本轮朗读材料已完成使命，清除（用户指定）
            topicReadings.clear()
        } else {
            // 记录错题，重试时避开
            topicAnswers.forEach { (k, v) ->
                wrongTried.getOrPut(k) { LinkedHashSet() }.add(v)
            }
            if (quizRetries < Prefs.failRetry) {
                quizRetries++
                LogRepo.log("result", "不合格，第$quizRetries 次重新通关")
                // 重试会重新进入学习/小测，必须清掉"已读过/已作答/已提交"守卫，
                // 否则会拒绝朗读、跳过选项直接交白卷
                answeredStems.clear()
                lastLearnedSig = null
                lastLearnX = null
                lastQuizStem = null
                quizSubmitSig = null
                quizSubmitWaits = 0
                // 两平台重试路径分流：
                // 旧平台失败页有"再次挑战"按钮 → 点它（直达小测）
                // 新平台失败页无任何按钮（56.jpg）→ back()返回，由详情页
                //   "立即开始"重进（服务端记进度，跳过朗读直达答题）
                // back() 已有微信主页拦截保护，不会退出小程序
                val retake = ScreenReader.findByContains(nodes, "再次挑战")
                if (retake != null && retake.visible) {
                    LogRepo.log("result", "点击[再次挑战]")
                    clickNode(retake)
                    sleep(rand(2000L, 3000L))
                } else {
                    LogRepo.log("result", "失败页无重试按钮，back()返回后重进当前任务")
                    host.back()
                    sleep(rand(2000L, 3000L))
                }
            } else {
                if (!resultCounted) {
                    resultCounted = true
                    statFailed++
                    Prefs.totalFailed = Prefs.totalFailed + 1
                    appendResultCsv(topicTitle, score, false, quizRetries)
                }
                LogRepo.log("result", "重试用尽，放弃该专题")
                val back = ScreenReader.findByContains(nodes, "返回通关列表")
                if (back != null) clickNode(back) else host.back()
                sleep(rand(1500L, 2500L))
                topicTitle = ""
                topicAnswers.clear()
                topicReadings.clear()
                quizRetries = 0
            }
        }
    }

    private fun appendResultCsv(topic: String, score: Int?, pass: Boolean, retries: Int) {
        try {
            val dir = File(appCtx.getExternalFilesDir(null), "export")
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "results.csv")
            val isNew = !f.exists()
            FileWriter(f, true).use { w ->
                if (isNew) w.appendLine("时间,专题,分数,结果,重试次数")
                val t = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
                w.appendLine("$t,\"${topic.replace("\"", "'")}\",${score ?: -1},${if (pass) "通过" else "未通过"},$retries")
            }
        } catch (_: Exception) {
        }
    }

    // ================= 麦克风权限弹窗 =================

    private fun looksLikeMicPermission(joined: String): Boolean =
        joined.contains("麦克风") && (joined.contains("允许") || joined.contains("允许使用"))

    private fun handleMicPermission(nodes: List<SNode>, joined: String) {
        LogRepo.log("perm", "检测到麦克风权限弹窗，自动允许")
        val prefer = nodes.filter { it.text.isNotEmpty() }.filter {
            it.text.contains("使用") && (it.text.contains("允许") || it.text.contains("允许使用"))
        }
        val fallback = nodes.filter { it.text == "允许" || it.text == "始终允许" || it.text == "仅在使用中允许" }
        val target = (prefer + fallback).firstOrNull()
        if (target != null) {
            clickNode(target)
            sleep(1200)
        } else {
            sleep(1500)
        }
    }

    // ================= 未知页面 =================

    private fun handleUnknown(nodes: List<SNode>, joined: String) {
        enterState(null)
        val sig = joined.hashCode().toString()
        if (sig != lastUnknownSig) {
            lastUnknownSig = sig
            unknownSince = System.currentTimeMillis()
            // 按页面内容区分dump：每种不同的未知页面都记录一次完整文本，便于排查
            dumpScreenOnce("unknown-$sig")
        }
        // 读到的如果是自己悬浮窗的文字，说明窗口选择异常
        if (joined.contains("提交跟读") || (joined.contains("运行中") && joined.contains("暂停") && joined.length < 300)) {
            host.onStatus("⚠ 屏幕读取异常，请重开无障碍开关")
        }
        // 微信主页（旧平台退出/任务中误切微信）？
        val wechatHome = listOf("消息", "通讯录", "发现", "我").all { k -> nodes.any { it.text == k } }
        if (wechatHome) {
            if (hadTask || topicTitle.isNotEmpty()) {
                // 任务中意外退出学习App：暂停并给出可行动提示（引擎无法自行重新进入）
                host.onStatus("⚠ 学习App已退出，请重新打开小邮助手到话术通关列表后点「▶ 继续」")
                host.vibrate(800)
                paused = true
                sleep(1500)
                return
            }
            host.onStatus("请打开「小邮助手」进入话术通关页面")
            sleep(1800)
            return
        }
        // 学习App外（前台包名≠宿主包名：桌面/微信/其他App）？
        // 仅提醒（不back不暂停），用户回到「小邮助手-通关话术」页面后自动恢复
        val curPkg = host.windowPackage()
        val outside = appPkg != null && curPkg != null && curPkg != appPkg
        if (outside) {
            host.onStatus("请回到「小邮助手-通关话术」页面")
            sleep(1800)
            return
        }
        // 宿主App内的未识别页（或appPkg未记录时的任意未知页）：
        // 同样提醒回到「小邮助手-通关话术」页面，60秒back恢复仅在此之后触发
        host.onStatus("请回到「小邮助手-通关话术」页面")
        if (topicTitle.isNotEmpty() && System.currentTimeMillis() - unknownSince > 60_000) {
            LogRepo.log("engine", "未知页面超60秒，返回键恢复")
            host.back()
            unknownSince = System.currentTimeMillis()
            unknownBackCount++
            if (unknownBackCount >= 3) {
                unknownBackCount = 0
                escapeTopic("未知页面无法恢复")
            }
            sleep(1800)
            return
        }
        sleep(1800)
    }

    // ================= 工具 =================

    /**
     * 点击节点：统一用真实坐标点按。
     * 微信小程序的WebView按钮不响应无障碍ACTION_CLICK合成点击（会返回成功但实际无效），
     * 真实触屏点按对原生控件和WebView控件均有效，故全量统一。
     */
    private fun clickNode(n: SNode): Boolean {
        return if (n.visible) {
            host.tap(n.centerX.toFloat(), n.centerY.toFloat())
        } else false
    }

    private fun updateDetail(s: String) {
        host.onDetail(s)
    }

    private fun sleep(ms: Long) {
        var remain = ms
        while (remain > 0 && !stopFlag) {
            if (paused) {
                Thread.sleep(400)
                continue
            }
            val s = minOf(remain, 250)
            Thread.sleep(s)
            remain -= s
        }
    }

    private fun rand(a: Long, b: Long): Long = if (b <= a) a else a + rng.nextLong(b - a + 1)
}
