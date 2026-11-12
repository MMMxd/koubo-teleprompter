package com.mxd.koubo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * 悬浮提词窗 v2（对标剪映提词器）：
 *  - 窗口即正文，无常驻按钮；顶栏 = 拖动区 + ⚙ 设置 + ✕ 关闭
 *  - ⚙ 展开控制面板：速度 / 字号 / 透明度 三滑条 + 跟随开关
 *  - 底部细进度条 · 跟随模式当前句琥珀高亮
 */
class OverlayService : Service() {

    private var wm: WindowManager? = null
    private var root: LinearLayout? = null
    private var bg: GradientDrawable? = null
    private var controller: PrompterController? = null
    private var engine: FollowEngine? = null
    private var scriptRef: DraftItem? = null
    private var scriptText: TextView? = null
    private var followPillField: PillToggle? = null
    private var playPillField: PillToggle? = null
    private var progressView: View? = null
    private var panel: LinearLayout? = null
    private var followOn = false
    private var opacityPct = 62
    private var winhPct = 42
    private var fontSp = 30f
    private lateinit var params: WindowManager.LayoutParams
    private val main = Handler(Looper.getMainLooper())

    private val uiTick = object : Runnable {
        override fun run() {
            val r = root ?: return
            val sc = r.findViewWithTag<ScrollView>("ovScroll") ?: return
            val pv = progressView ?: return
            val maxScroll = maxOf(sc.getChildAt(0).height - sc.height, 1)
            val pct = (sc.scrollY.toFloat() / maxScroll).coerceIn(0f, 1f)
            val lp = pv.layoutParams as LinearLayout.LayoutParams
            lp.width = (r.width * pct).toInt()
            pv.layoutParams = lp
            main.postDelayed(this, 400)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (root != null) teardown() // 已在运行：无缝切换到新稿
        val id = intent?.getLongExtra("id", -1L) ?: -1L
        val scripts = DraftStore.load(this)
        val script = scripts.firstOrNull { it.id == id } ?: scripts.firstOrNull()
        if (script == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        scriptRef = script
        buildOverlay(script)
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else Notification.Builder(this)
        val notif = b
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.overlay_running))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun applyOpacity() {
        bg?.setColor(Color.argb(opacityPct * 255 / 100, 12, 12, 18))
    }

    private fun applyWinH() {
        params.height = (resources.displayMetrics.heightPixels * winhPct / 100)
        try {
            root?.let { wm?.updateViewLayout(it, params) }
        } catch (_: Exception) {
        }
    }

    /* ── 悬浮窗构建 ── */

    private fun speedPctToSeek(v: Float): Int = (((v - 0.02f) / 7.98f) * 100).toInt().coerceIn(0, 100)
    private fun dpf(v: Float): Int = (v * resources.displayMetrics.density).toInt()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float, color: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
    }

    private fun makeSeek(max: Int, init: Int, onChange: (Int) -> Unit): SeekBar = SeekBar(this).apply {
        this.max = max
        progress = init.coerceIn(0, max)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) onChange(value)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun buildOverlay(script: DraftItem) {
        val dm = resources.displayMetrics
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val bgDrawable = GradientDrawable().apply {
            applyOpacityTo(this)
            cornerRadius = dpf(20f).toFloat()
        }
        bg = bgDrawable

        val rootLl = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bgDrawable
            setPadding(dp(10), dp(4), dp(10), dp(10))
        }

        // ── 顶栏：拖动区(带把手) + ⚙ + ✕ ──
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val dragZone = FrameLayout(this)
        val grip = TextView(this).apply {
            text = "———"
            textSize = 8f
            gravity = Gravity.CENTER
            setTextColor(0x55FFFFFF)
        }
        dragZone.addView(
            grip,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14))
        )
        bindDrag(dragZone)
        topBar.addView(
            dragZone,
            LinearLayout.LayoutParams(0, dp(22), 1f)
        )

        topBar.addView(
            topIcon("⚙", 0xFFEDEDF2.toInt()) { togglePanel() },
            LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginStart = dp(4) }
        )
        topBar.addView(
            topIcon("✕", 0xFFFF8A8A.toInt()) { stopSelf() },
            LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginStart = dp(4) }
        )
        rootLl.addView(
            topBar,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        // ── 正文滚动区 ──
        val scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            tag = "ovScroll"
        }
        val prefs = getSharedPreferences(PrompterActivity.PREFS, MODE_PRIVATE)
        fontSp = prefs.getFloat(PrompterActivity.KEY_FONT, 46f) * 0.62f
        winhPct = prefs.getInt(PrompterActivity.KEY_WINH, 42).coerceIn(25, 80)
        val tv = TextView(this).apply {
            textSize = fontSp
            setTextColor(0xFFF5F5F0.toInt())
            setLineSpacing(dp(5).toFloat(), 1.15f)
            setPadding(dp(6), dp(2), dp(6), 0)
            setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), 0x99000000.toInt())
            text = script.content
        }
        scroll.addView(
            tv,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        scroll.post { tv.setPadding(dp(6), dp(4), dp(6), (scroll.height * 0.7f).toInt()) }
        rootLl.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        scriptText = tv

        // ── 底部细进度条 ──
        val track = LinearLayout(this).apply { setBackgroundColor(0x22FFFFFF) }
        val pv = View(this).apply { setBackgroundColor(0xFF6C5CE7.toInt()) }
        progressView = pv
        track.addView(pv, LinearLayout.LayoutParams(0, dp(3)))
        rootLl.addView(
            track,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)).apply { topMargin = dp(8) }
        )

        // ── 控制面板（⚙ 展开，默认隐藏） ──
        val panelLl = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        // 滚动播放开关（匀速模式用；跟随开启时自动接管）
        val playRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(2))
        }
        playRow.addView(
            TextView(this).apply {
                text = "滚动"
                textSize = 12f
                setTextColor(0xFFE8E8EE.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        val playPill = PillToggle(this, "滚动 · 关", "滚动 · 开") { on ->
            if (on) controller?.play() else controller?.pause()
        }
        playRow.addView(playPill.view)
        playPillField = playPill
        panelLl.addView(playRow)

        val speedVal = label("2.0", 11f, 0xFFFFFFFF.toInt())
        speedVal.gravity = Gravity.CENTER
        speedVal.layoutParams = LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT)
        val speedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        speedRow.addView(label("速", 12f, 0xFFE8E8EE.toInt()), LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.WRAP_CONTENT))
        speedRow.addView(makeSeek(100, speedPctToSeek(prefs.getFloat(PrompterActivity.KEY_SPEED, 2f))) { pct ->
            val v = 0.02f + pct * 7.98f / 100f
            controller?.speedCps = v
            speedVal.text = String.format("%.1f", v)
        })
        speedRow.addView(speedVal)
        panelLl.addView(speedRow)

        val fontVal = label("${fontSp.toInt()}", 11f, 0xFFFFFFFF.toInt())
        fontVal.gravity = Gravity.CENTER
        fontVal.layoutParams = LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT)
        val fontRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        fontRow.addView(label("字", 12f, 0xFFE8E8EE.toInt()), LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.WRAP_CONTENT))
        fontRow.addView(makeSeek(72, (fontSp - 18f).toInt()) { v ->
            fontSp = 18f + v
            scriptText?.textSize = fontSp
            fontVal.text = "${fontSp.toInt()}"
        })
        fontRow.addView(fontVal)
        panelLl.addView(fontRow)

        val opacityVal = label("$opacityPct%", 11f, 0xFFFFFFFF.toInt())
        opacityVal.gravity = Gravity.CENTER
        opacityVal.layoutParams = LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT)
        val opacityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        opacityRow.addView(label("透", 12f, 0xFFE8E8EE.toInt()), LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.WRAP_CONTENT))
        opacityRow.addView(makeSeek(90, opacityPct) { v ->
            opacityPct = v.coerceAtLeast(10)
            opacityVal.text = "${opacityPct}%"
            applyOpacity()
        })
        opacityRow.addView(opacityVal)
        panelLl.addView(opacityRow)

        // 窗体高度（拉长/拉短）
        val winhVal = label("$winhPct%", 11f, 0xFFFFFFFF.toInt())
        winhVal.gravity = Gravity.CENTER
        winhVal.layoutParams = LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT)
        val winhRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        winhRow.addView(label("高", 12f, 0xFFE8E8EE.toInt()), LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.WRAP_CONTENT))
        winhRow.addView(makeSeek(55, winhPct - 25) { v ->
            winhPct = v + 25
            winhVal.text = "$winhPct%"
            prefs.edit().putInt(PrompterActivity.KEY_WINH, winhPct).apply()
            applyWinH()
        })
        winhRow.addView(winhVal)
        panelLl.addView(winhRow)

        val followRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(2))
        }
        followRow.addView(
            TextView(this).apply {
                text = "语音跟随（念到哪滚到哪）"
                textSize = 12f
                setTextColor(0xFFE8E8EE.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        val followPill = PillToggle(this, "跟随 · 关", "跟随 · 开") { on ->
            if (on) startFollow() else stopFollow()
        }
        followPillField = followPill
        followRow.addView(followPill.view)
        panelLl.addView(followRow)

        panel = panelLl
        rootLl.addView(panelLl)

        params = WindowManager.LayoutParams(
            (dm.widthPixels * 0.88f).toInt(),
            (dm.heightPixels * winhPct / 100),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels * 0.06f).toInt()
            y = (dm.heightPixels * 0.14f).toInt()
        }

        engine = FollowEngine(script.content)
        val c = PrompterController(scroll, tv, engine)
        c.speedCps = prefs.getFloat(PrompterActivity.KEY_SPEED, 2f)
        controller = c
        c.onTick = { playPillField?.setSilent(controller?.playing == true) }
        PrompterController.bindTouchPause(scroll, c)
        main.postDelayed(uiTick, 400)

        try {
            wm?.addView(rootLl, params)
        } catch (e: Exception) {
            Toast.makeText(this, "悬浮窗启动失败：${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        root = rootLl
    }

    private fun applyOpacityTo(d: GradientDrawable) {
        d.setColor(Color.argb(opacityPct * 255 / 100, 12, 12, 18))
    }

    private fun topIcon(label: String, tint: Int, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(0, dp(4), 0, dp(4))
        background = GradientDrawable().apply {
            setColor(0x22FFFFFF)
            cornerRadius = dpf(999f).toFloat()
        }
        setTextColor(tint)
        setOnClickListener { onClick() }   // ← 关键修复：之前漏接点击事件，⚙/✕ 是死图标
    }

    private fun togglePanel() {
        val p = panel ?: return
        p.visibility = if (p.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    /* ── 拖动 ── */
    private fun bindDrag(v: View) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = params.x
                    startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - downX).toInt()
                    params.y = startY + (e.rawY - downY).toInt()
                    try {
                        wm?.updateViewLayout(root, params)
                    } catch (_: Exception) {
                    }
                    true
                }
                else -> false
            }
        }
    }

    /* ── 语音跟随（识别引擎跑在 :speech 隔离进程，native 崩溃只死引擎不死悬浮窗） ── */

    private var mic: AudioRecord? = null
    private var micThread: Thread? = null
    @Volatile private var pumping = false
    private var uiReceiver: BroadcastReceiver? = null
    private var lastAlive = 0L

    private val watchdog = object : Runnable {
        override fun run() {
            if (!followOn) return
            if (System.currentTimeMillis() - lastAlive > 12000) {
                // :speech 进程被 sherpa exit 杀掉了：提示 + 复位，重新点跟随即重试
                Toast.makeText(this@OverlayService, "识别引擎异常退出，请重新点一次「跟随 · 开」", Toast.LENGTH_LONG).show()
                stopFollowInternal()
                followPillField?.setSilent(false)
                return
            }
            main.postDelayed(this, 3000)
        }
    }

    private fun startFollow() {
        val eng = engine ?: return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "跟随需要麦克风：已打开应用信息，请授权麦克风后回来打开跟随", Toast.LENGTH_LONG).show()
            openAppDetails()
            followPillField?.setSilent(false)
            return
        }
        eng.reset()
        controller?.engine = eng
        controller?.follow = true
        controller?.snapToEngine()
        controller?.play()
        followOn = true
        followPillField?.setSilent(true)

        val br = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    FollowIpc.ACTION_TEXT -> {
                        lastAlive = System.currentTimeMillis()
                        eng.feed(i.getStringExtra(FollowIpc.EXTRA_TEXT) ?: "")
                        applyFollowHighlight()
                    }
                    FollowIpc.ACTION_READY -> {
                        lastAlive = System.currentTimeMillis()
                        followPillField?.setSilent(true)
                        startMicPump()
                    }
                    FollowIpc.ACTION_ERROR -> {
                        lastAlive = System.currentTimeMillis()
                        Toast.makeText(
                            this@OverlayService,
                            "跟随失败：${i.getStringExtra(FollowIpc.EXTRA_MSG)}",
                            Toast.LENGTH_LONG
                        ).show()
                        stopFollowInternal()
                        followPillField?.setSilent(false)
                    }
                    FollowIpc.ACTION_ALIVE -> lastAlive = System.currentTimeMillis()
                }
            }
        }
        uiReceiver = br
        ContextCompat.registerReceiver(
            this, br,
            IntentFilter().apply {
                addAction(FollowIpc.ACTION_TEXT)
                addAction(FollowIpc.ACTION_READY)
                addAction(FollowIpc.ACTION_ERROR)
                addAction(FollowIpc.ACTION_ALIVE)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        lastAlive = System.currentTimeMillis()
        startService(Intent(this, FollowService::class.java))
        main.postDelayed(watchdog, 3000)
    }

    private fun stopFollowInternal() {
        followOn = false
        controller?.follow = false
        stopMicPump()
        uiReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        uiReceiver = null
        main.removeCallbacks(watchdog)
        try { stopService(Intent(this, FollowService::class.java)) } catch (_: Exception) {}
        scriptRef?.let { scriptText?.text = it.content }
    }

    private fun stopFollow() {
        stopFollowInternal()
        followPillField?.setSilent(false)
    }

    private fun startMicPump() {
        if (pumping) return
        pumping = true
        micThread = Thread {
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(3200)
                val rec = AudioRecord(
                    MediaRecorder.AudioSource.MIC, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
                )
                rec.startRecording()
                mic = rec
                val buf = ShortArray(1600) // 100ms @16kHz
                while (pumping) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) break
                    val bb = java.nio.ByteBuffer.allocate(n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    bb.asShortBuffer().put(buf, 0, n)
                    sendBroadcast(
                        Intent(FollowIpc.ACTION_PCM).setPackage(packageName)
                            .putExtra(FollowIpc.EXTRA_PCM, bb.array())
                    )
                }
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
                mic = null
            } catch (_: Throwable) {
                mic = null
            }
        }.also { it.start() }
    }

    private fun stopMicPump() {
        pumping = false
        try { mic?.stop() } catch (_: Exception) {}
        try { mic?.release() } catch (_: Exception) {}
        mic = null
    }

    private fun applyFollowHighlight() {
        val content = scriptRef?.content ?: return
        if (content.isEmpty()) return
        val sb = SpannableStringBuilder(content)
        val offset = engine?.originalOffset()?.coerceIn(0, content.length - 1) ?: return
        val stops = charArrayOf('。', '！', '？', '!', '?', '\n', '…', '；')
        var from = offset
        while (from > 0 && stops.none { content[from - 1] == it }) from--
        var to = offset
        while (to < content.length && stops.none { content[to] == it }) to++
        if (to < content.length) to++
        val end = to.coerceAtMost(content.length)
        sb.setSpan(BackgroundColorSpan(0x30FFD54D), from, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(0xFFFFD54D.toInt()), from, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        scriptText?.text = sb
    }

    private fun openAppDetails() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(uiTick)
        teardown()
    }

    private fun teardown() {
        followOn = false
        stopMicPump()
        uiReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        uiReceiver = null
        main.removeCallbacks(watchdog)
        try { stopService(Intent(this, FollowService::class.java)) } catch (_: Exception) {}
        controller?.release()
        controller = null
        followPillField = null
        playPillField = null
        progressView = null
        panel = null
        scriptText = null
        root?.let {
            try {
                wm?.removeView(it)
            } catch (_: Exception) {
            }
        }
        root = null
    }

    companion object {
        private const val CHANNEL = "ticiqi_overlay"
        private const val NOTIF_ID = 1
    }
}
