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

    // ---------- 状态 ----------
    private var state: String? = null
    private var stateEnter = 0L
    private var listTabClicked = false
    private var listScrolled = false
    private var listScrolledAttempts = 0
    private var topicTitle = ""
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
    private var lastLearnedSig: String? = null   // 刚读完并已点下一题的那条（防页面未切换时重复朗读）
    private var lastLearnX: String? = null       // 学习进度x，前进时刷新看门狗
    private var lastQuizStem: String? = null     // 当前小测题干，切题时刷新看门狗
    private var quizSubmitSig: String? = null    // 已点过"提交"的题干（防重复提交；重试/换题时复位）
    private val answeredStems = LinkedHashSet<String>() // 本专题已作答过的题

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
                // 预热：首次合成常有延迟，先空读一词，避免第一段录音只录到半截
                player.speakAndWait("预热")
            }
            if (Prefs.llmReady) {
                llm = LlmClient(LlmConfig(Prefs.llmBaseUrl, Prefs.llmApiKey, Prefs.llmModel))
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

        when {
            looksLikeMicPermission(joined) -> handleMicPermission(nodes, joined)
            joined.contains("本次通关说明") -> handleDesc(nodes)
            // 反馈/结果页要先于小测判断（弹层后面能看到题目文本）
            joined.contains("通关反馈") -> handleFeedback(nodes)
            joined.contains("通关结果") -> handleResult(nodes, joined)
            isQuiz(joined, nodes) -> handleQuiz(nodes)
            // 学习页：读完朗读后"请朗读以下文字"可能变为"朗读完成"，用进度头兜底
            isLearning(joined) -> handleLearning(nodes, joined)
            joined.contains("话术通关") -> handleList(nodes, joined)
            else -> handleUnknown(nodes, joined)
        }
        watchdog()
    }

    private val learnHeaderRe = Regex("学习\\(\\d+/\\d+\\)")

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
        lastLearnedSig = null
        lastLearnX = null
        lastQuizStem = null
        quizSubmitSig = null
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
        // 每个新专题优先回到点按录音模式（点按历史表现最好；
        // 长按模式只在点按失败时作为本专题内的兜底）
        micMode = 1
        micModeTriedHold = false
        answeredStems.clear()
        lastLearnedSig = null
        lastLearnX = null
        lastQuizStem = null
        quizSubmitSig = null
        resultCounted = false
        passedAwaitingAdvance = null
        passAdvanceFails = 0
        quizRetries = 0
        stubRetries = 0
        LogRepo.log("list", "进入专题: $topicTitle")
        host.onStatus("刷: ${topicTitle.take(18)}")
        clickNode(card)
        sleep(rand(1500L, 2600L))
        enterState(null)
    }

    /** 兜底找卡片：标签行下方最上方的"标题样"文本（排除元数据行） */
    private fun fallbackCard(nodes: List<SNode>): SNode? {
        val tab = ScreenReader.findByPrefix(nodes, "未通关")
            ?: ScreenReader.findByPrefix(nodes, "已通关")
            ?: return null
        if (!tab.visible) return null
        val tabBottom = tab.bounds.bottom
        return nodes.filter { n ->
            n.visible && n.text.length in 6..80 && n.bounds.top > tabBottom + 30 &&
                    !n.text.contains("#") &&
                    !n.text.contains("长期有效") &&
                    !n.text.contains("话术通关") &&
                    !n.text.contains("输入关键词") &&
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
        val go = ScreenReader.findByContains(nodes, "立即开始")
            ?: ScreenReader.findByContains(nodes, "开始学习")
        if (go != null) {
            LogRepo.log("desc", "点击[立即开始]")
            clickNode(go)
            sleep(rand(1400L, 2400L))
            enterState(null)
        } else {
            sleep(1000)
        }
    }

    // ================= 02/03 学习页（朗读） =================

    private fun handleLearning(nodes: List<SNode>, joined: String) {
        enterState("LEARN")
        val progress = Regex("学习\\((\\d+)/(\\d+)\\)").find(joined)
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
                enterState(null)
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
                enterState(null)
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
            // 已通过待翻页时不标记"已读"（翻页成功后才标记）
            if (passedAwaitingAdvance == null) lastLearnedSig = sig
            sleep(rand(900L, 1600L))
            enterState(null)
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
                    j.contains("通关反馈") || j.contains("通关结果")
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
        val beforeX = host.root()?.let { learningSig(ScreenReader.collect(it)).first } ?: -2
        while (System.currentTimeMillis() - start < maxWaitMs && !stopFlag) {
            val root = host.root()
            if (root == null) {
                sleep(600)
                continue
            }
            val nodes = ScreenReader.collect(root)
            val j = nodes.joinToString("\n") { it.display }
            val quizOrResult = j.contains("【单选】") || j.contains("【多选】") || j.contains("【判断】") ||
                    j.contains("通关反馈") || j.contains("通关结果")
            if (quizOrResult) return true
            val afterX = learningSig(nodes).first
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
        val btn = ScreenReader.findByContains(nodes, "下一题")
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
                j3.contains("通关反馈") || j3.contains("通关结果")
            ) return true
            if (learningSig(n3).first > beforeX) return true
        }
        return false
    }

    private fun learningSig(nodes: List<SNode>): Pair<Int, String> {
        val m = Regex("学习\\((\\d+)/(\\d+)\\)").find(nodes.joinToString(" ") { it.display })
        val x = m?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val label = nodes.filter { it.text.length in 6..40 && !it.text.contains("请朗读") }
            .minByOrNull { it.bounds.top }?.text?.take(20).orEmpty()
        return x to label
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
        if (joined.contains("【单选】") || joined.contains("【多选】") || joined.contains("【判断】")) return true
        // 兜底：有小测进度且有"字母、"选项行
        return joined.contains("小测(") &&
                ScreenReader.findAll(nodes) { Regex("^[A-H]、").containsMatchIn(it) }.isNotEmpty()
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
            it.text.contains("【单选】") || it.text.contains("【多选】") || it.text.contains("【判断】")
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
        }
        updateDetail("小测：${stemText.take(24)}")
        LogRepo.log("quiz", "题干=$stemText 选项=${availableLetters.joinToString("")}")

        // ---------- 决定答案：缓存 → AI → 蒙题（同一题只作答一次，防止翻页失败重复点击取消选中） ----------
        if (stemKey in answeredStems) {
            LogRepo.log("quiz", "本题已作答过，跳过选项直接翻页")
        } else {
            var answer: String? = QuestionStore.get(stemText)?.answer
            var source = if (answer != null) "cache" else ""
            if (answer == null && llm != null) {
                host.onStatus("AI答题中…")
                answer = llm!!.ask(stemText, opts.map { it.first to it.second })
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
                answer = guessAnswer(isMulti, availableLetters, wrongTried[stemKey].orEmpty())
                source = if (source.isEmpty()) "guess" else "${source}+guess"
                statGuess++
            }
            // 与可用选项求交集；排序去重避免重复点击取消选中
            val letters = answer.orEmpty().uppercase().filter { it in availableLetters }.toSortedSet()
            val finalLetters = if (letters.isEmpty()) guessAnswer(isMulti, availableLetters, emptySet())
            else letters.joinToString("")
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
        // 已提交过小测且尚未翻页：只等待，绝不重复点提交（A4修复，防重复提交）
        if (quizSubmitSig == stemKey) {
            LogRepo.log("quiz", "本题已提交过，等待服务器跳转")
            sleep(2000)
            enterState(null)
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
                enterState(null)
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
                if (j2.contains("通关结果") || j2.contains("通关反馈")) break
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
            if (!j2.contains(stemText.take(10)) || j2.contains("通关反馈") || j2.contains("通关结果")) break
        }
        enterState(null)
    }

    private fun guessAnswer(isMulti: Boolean, available: List<Char>, exclude: Set<String>): String {
        // 排除本轮重试中已被证实错误的选项
        val pool = available.filter { c -> exclude.none { it.contains(c) } }
        return if (isMulti) {
            if (Prefs.guessMulti == "random" || (pool.isNotEmpty() && pool.size < available.size)) {
                // 随机模式，或"全选"已被证伪时：从未证伪的选项里随机挑一个子集
                val src = if (pool.isEmpty()) available else pool
                val count = 1 + rng.nextInt(src.size)
                src.shuffled(rng).take(count).sorted().joinToString("")
            } else {
                // 全选
                available.joinToString("")
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

    // ================= 05 通关反馈 =================

    /**
     * 通关反馈：先点最后一颗星（5星好评），再点提交。
     * 星星定位两级：① 无文字方形图标（弹层内、排除关闭按钮）取最右；
     * ② 几何兜底——以"请对本次通关…评价"提示行为锚点，第5颗星中心
     * ≈ (屏宽*0.40, 锚点bottom+55px)（5星行占屏宽约40%，靠左排列）。
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
                    it.text.isEmpty() &&
                    (it.desc.contains("星") || it.desc.contains("star") || true)
        }.maxByOrNull { it.bounds.left }

        fun pickSubmit(list: List<SNode>): SNode? =
            list.filter { it.text == "提交" }.maxByOrNull { it.bounds.bottom }

        fun starFallbackPoint(): PointF? {
            val anchor = after.firstOrNull {
                it.text.contains("请对本次通关") || it.text.contains("进行评价")
            } ?: return null
            val sw = screenBounds()?.width() ?: return null
            // 第5颗星：5星行靠左排列，占屏宽约40%，最后一颗中心约在屏宽40%处
            return PointF(sw * 0.40f, anchor.bounds.bottom + 55f)
        }

        // 1) 最后一颗星 = 好评
        val star = pickStar(after)
        if (star != null) {
            LogRepo.log("feedback", "点击最后一颗星(好评): ${star.bounds}")
            clickNode(star)
            sleep(600)
        } else {
            val p = starFallbackPoint()
            if (p != null) {
                LogRepo.log("feedback", "未找到星星节点，按几何位置点击第5星: $p")
                host.tap(p.x, p.y)
                sleep(600)
            } else {
                LogRepo.log("feedback", "未定位到星级（无节点无锚点），直接提交")
            }
        }

        // 2) 提交
        val submit = pickSubmit(after)
        if (submit == null) {
            sleep(1000); return
        }
        clickNode(submit)
        sleep(1600)

        // 3) 验证：弹层仍在则补一轮（几何星级→提交），仍卡住尝试关闭
        val root3 = host.root() ?: return
        val after3 = ScreenReader.collect(root3, includeAll = true)
        if (!after3.joinToString("\n") { it.display }.contains("通关反馈")) {
            LogRepo.log("feedback", "反馈提交成功")
            enterState(null)
            return
        }
        LogRepo.log("feedback", "反馈仍在，补点星级后重试提交")
        val star2 = pickStar(after3)
        if (star2 != null) {
            clickNode(star2)
            sleep(600)
        } else {
            starFallbackPoint()?.let {
                host.tap(it.x, it.y)
                sleep(600)
            }
        }
        pickSubmit(after3)?.let { clickNode(it) }
        sleep(1600)
        val root4 = host.root() ?: return
        val after4 = ScreenReader.collect(root4, includeAll = true)
        if (after4.joinToString("\n") { it.display }.contains("通关反馈")) {
            // 第三轮：横向扫描。几何比例两轮都失败说明估计偏差，
            // 不再猜——在星级行高度从左到右扫一排候选点。
            // 星级组件"最后一次点击的位置决定星级"，扫描终点偏右即可命中第5星。
            LogRepo.log("feedback", "两轮未成功，启动横向扫描星级")
            sweepStars(after3)
            pickSubmit(after3)?.let { clickNode(it) }
            sleep(1600)
            val root5 = host.root() ?: return
            val after5 = ScreenReader.collect(root5, includeAll = true)
            if (!after5.joinToString("\n") { it.display }.contains("通关反馈")) {
                LogRepo.log("feedback", "扫描轮提交成功")
                enterState(null)
                return
            }
            LogRepo.log("feedback", "扫描后仍未提交，尝试关闭弹层")
            val close = after5.firstOrNull {
                it.display == "×" || it.display == "✕" || it.desc.contains("关闭")
            }
            close?.let { clickNode(it) }
            sleep(1200)
        }
        enterState(null)
    }

    /**
     * 星级行横向扫描：在"评价提示行"下方的高度带上，从屏宽10%到50%逐点点击。
     * 星级组件以最后一次点击为准——扫描覆盖真实星星行时，最右侧的点即5星。
     * 每点后若弹层消失（提交恰好生效）立即返回。
     */
    private fun sweepStars(ref: List<SNode>) {
        val anchor = ref.firstOrNull {
            it.text.contains("请对本次通关") || it.text.contains("进行评价")
        }
        val baseY = anchor?.bounds?.bottom?.plus(55f)
            ?: screenBounds()?.let { it.top + (it.height() * 0.82).toInt() }?.toFloat()
            ?: return
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
        // 退出小程序。等排名区加载标志出现（最多10秒）再判定与点击。
        var nodes = nodes0
        var joined = joined0
        var loadWaited = 0
        while (loadWaited < 10_000 && !stopFlag) {
            val j = nodes.joinToString("\n") { it.display }
            if (j.contains("通关排名") || j.contains("支行网点") ||
                Regex("已通关\\(\\d+\\)").containsMatchIn(j)
            ) break
            sleep(700)
            loadWaited += 700
            val r = host.root() ?: break
            nodes = ScreenReader.collect(r)
            joined = nodes.joinToString("\n") { it.display }
        }
        if (loadWaited > 0) LogRepo.log("result", "等待结果页数据加载 ${loadWaited}ms")
        val score = nodes.mapNotNull {
            Regex("^\\s*(\\d{1,3})\\s*分\\s*$").find(it.text)?.groupValues?.get(1)?.toIntOrNull()
        }.firstOrNull()
            ?: Regex("首次通关成绩\\s*(\\d{1,3})\\s*分").find(joined)?.groupValues?.get(1)?.toIntOrNull()
        val pass = joined.contains("恭喜通关") || joined.contains("通关成功") || (score != null && score >= 60)
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
            enterState(null)
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
                val retry = ScreenReader.findByContains(nodes, "重新通关")
                if (retry != null) {
                    clickNode(retry)
                    sleep(rand(2000L, 3000L))
                    // 可能出现"确认重新通关"弹窗
                    host.root()?.let { r ->
                        val nn = ScreenReader.collect(r)
                        nn.firstOrNull { it.text in setOf("确定", "确认") }?.let {
                            clickNode(it)
                            LogRepo.log("result", "点击重试确认弹窗")
                            sleep(1200)
                        }
                    }
                    enterState(null)
                } else {
                    host.back(); sleep(1500); enterState(null)
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
                quizRetries = 0
                enterState(null)
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
        // 微信主页？
        val wechatHome = listOf("消息", "通讯录", "发现", "我").all { k -> nodes.any { it.text == k } }
        if (wechatHome) {
            host.onStatus("请在微信中打开小程序学习页面")
        } else {
            host.onStatus("等待页面…")
        }
        // 在专题中卡在未知页面超过60秒：返回键退出
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
