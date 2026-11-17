package com.mxd.koubo

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** 全屏提词页：匀速滚动 / 语音跟随 / 一键转悬浮窗 */
class PrompterActivity : AppCompatActivity() {

    private lateinit var scroll: ScrollView
    private lateinit var scriptText: TextView
    private lateinit var status: TextView
    private lateinit var countdown: TextView
    private lateinit var speedVal: TextView
    private lateinit var fontVal: TextView
    private lateinit var btnPlay: Button
    private lateinit var btnFollow: Button

    private lateinit var controller: PrompterController
    private lateinit var engine: FollowEngine
    private var script: DraftItem? = null
    private var mic: AudioRecord? = null
    private var micThread: Thread? = null
    @Volatile private var pumping = false
    private var uiReceiver: BroadcastReceiver? = null
    private var lastAlive = 0L
    private var followOn = false
    private var fontSp = 46f
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        setContentView(R.layout.activity_prompter)

        val id = intent.getLongExtra("id", -1L)
        val scripts = DraftStore.load(this)
        script = scripts.firstOrNull { it.id == id } ?: scripts.firstOrNull()
        if (script == null) {
            finish()
            return
        }
        val s = script!!

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        scroll = findViewById(R.id.scroll)
        scriptText = findViewById(R.id.scriptText)
        status = findViewById(R.id.status)
        countdown = findViewById(R.id.countdown)
        speedVal = findViewById(R.id.speedVal)
        fontVal = findViewById(R.id.fontVal)
        btnPlay = findViewById(R.id.btnPlay)
        btnFollow = findViewById(R.id.btnFollow)

        fontSp = prefs.getFloat(KEY_FONT, 46f)
        scriptText.textSize = fontSp
        scriptText.text = s.content
        // 底部留白 = 72% 屏高，让最后一行刚好停在阅读线上；同时把基准线对齐到跟随目标位置
        scroll.post {
            val pad = (20 * resources.displayMetrics.density).toInt()
            scriptText.setPadding(pad, 0, pad, (scroll.height * 0.72f).toInt())
            findViewById<View>(R.id.readLine).y = scroll.height * PrompterController.READ_LINE_RATIO
        }

        engine = FollowEngine(s.content)
        controller = PrompterController(scroll, scriptText, engine)
        controller.speedCps = prefs.getFloat(KEY_SPEED, 2f)
        controller.onTick = {
            btnPlay.text = if (controller.playing) getString(R.string.btn_pause) else getString(R.string.btn_play)
        }
        controller.onEnded = { status.text = "已到底" }
        speedVal.text = String.format("速度 %.1f", controller.speedCps)
        PrompterController.bindTouchPause(scroll, controller)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        btnPlay.setOnClickListener {
            if (controller.playing) controller.pause()
            else if (controller.follow) controller.play() // 跟随模式无需倒计时
            else startCountdown()
        }
        // 速度 / 字号滑动条
        val seekSpeed = findViewById<SeekBar>(R.id.seekSpeed)
        seekSpeed.max = 79 // 0.02 ~ 8.0
        seekSpeed.progress = ((controller.speedCps - 0.02f) / 7.98f * 79).toInt().coerceIn(0, 79)
        seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) setSpeed(0.02f + value * 0.101f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        val seekFont = findViewById<SeekBar>(R.id.seekFont)
        seekFont.max = 72 // 18 ~ 90
        seekFont.progress = fontSp.toInt() - 18
        seekFont.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) setFont(18f + value)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        // 背景透明度（10~100%）
        val tpPrefs = getSharedPreferences(PrompterActivity.PREFS, MODE_PRIVATE)
        var opacity = tpPrefs.getInt(KEY_OPACITY, 100).coerceIn(10, 100)
        val opacityVal = findViewById<TextView>(R.id.opacityVal)
        val root = findViewById<View>(R.id.prompterRoot)
        fun refreshOpacity() {
            opacityVal.text = "$opacity%"
            root.background = android.graphics.drawable.ColorDrawable(
                android.graphics.Color.argb(opacity * 255 / 100, 12, 12, 18)
            )
        }
        refreshOpacity()
        val seekOpacity = findViewById<SeekBar>(R.id.seekOpacity)
        seekOpacity.max = 90
        seekOpacity.progress = opacity - 10
        seekOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                opacity = value + 10
                refreshOpacity()
                tpPrefs.edit().putInt(KEY_OPACITY, opacity).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        btnFollow.setOnClickListener { toggleFollow() }
        if (BuildConfig.DEBUG) {
            // 长按跟随 = 装填模拟音源（filesDir/test_follow.wav），供无麦克风环境端到端调试跟随
            btnFollow.setOnLongClickListener { loadSimWav() }
        }
        findViewById<Button>(R.id.btnFloat).setOnClickListener { launchOverlay() }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
    }

    private fun setSpeed(v: Float) {
        val nv = v.coerceIn(0.5f, 8f)
        controller.speedCps = nv
        speedVal.text = String.format("速度 %.1f", nv)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat(KEY_SPEED, nv).apply()
    }

    private fun setFont(v: Float) {
        fontSp = v.coerceIn(20f, 90f)
        scriptText.textSize = fontSp
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat(KEY_FONT, fontSp).apply()
    }

    private fun startCountdown() {
        var n = 3
        countdown.visibility = View.VISIBLE
        fun tick() {
            if (n == 0) {
                countdown.visibility = View.GONE
                controller.play()
                return
            }
            countdown.text = n.toString()
            n--
            handler.postDelayed({ tick() }, 700)
        }
        tick()
    }

    private fun toggleFollow() {
        if (followOn) {
            stopFollow()
            status.text = getString(R.string.follow_off)
            return
        }
        // 模拟跟随（仅 debug）：长按跟随按钮装填 filesDir/test_follow.wav，无需麦克风权限
        if (simPcm == null && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        startFollow()
    }

    /* ── 模拟跟随（仅 debug 构建可用）：把 WAV 当麦克风输入喂给跟随管线 ── */

    private var simPcm: ShortArray? = null
    private var simPos = 0

    /** 长按「跟随」装填模拟音源：filesDir/test_follow.wav（16kHz 单声道 16bit PCM） */
    private fun loadSimWav(): Boolean {
        val f = java.io.File(filesDir, "test_follow.wav")
        if (!f.exists()) {
            Toast.makeText(this, "模拟音源不存在：test_follow.wav", Toast.LENGTH_SHORT).show()
            return true
        }
        return try {
            val bytes = f.readBytes()
            val sb = java.nio.ByteBuffer.wrap(bytes, 44, bytes.size - 44)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val pcm = ShortArray(sb.remaining()).also { sb.get(it) }
            simPcm = pcm
            simPos = 0
            val sec = pcm.size / 16000.0
            Toast.makeText(this, String.format("模拟跟随已装填：%.0f 秒语音（念完自动停）", sec), Toast.LENGTH_LONG).show()
            true
        } catch (e: Exception) {
            Toast.makeText(this, "WAV 解析失败：${e.message}", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun startFollow() {
        engine.reset()
        controller.engine = engine
        controller.follow = true
        controller.snapToEngine()
        controller.play()
        followOn = true
        btnFollow.setTextColor(0xFFFFD54F.toInt())
        status.text = "加载识别模型…"

        val br = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    FollowIpc.ACTION_TEXT -> {
                        lastAlive = System.currentTimeMillis()
                        engine.feed(i.getStringExtra(FollowIpc.EXTRA_TEXT) ?: "")
                        applyFollowHighlight()
                    }
                    FollowIpc.ACTION_READY -> {
                        lastAlive = System.currentTimeMillis()
                        status.text = getString(R.string.follow_on)
                        startMicPump()
                    }
                    FollowIpc.ACTION_ERROR -> {
                        lastAlive = System.currentTimeMillis()
                        status.text = "跟随失败：${i.getStringExtra(FollowIpc.EXTRA_MSG)}"
                        stopFollowInternal()
                        btnFollow.setTextColor(0xFFFFFFFF.toInt())
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
        handler.postDelayed(watchdog, 3000)
    }

    private fun startMicPump() {
        if (pumping) return
        pumping = true
        micThread = Thread {
            try {
                if (simPcm == null) {
                    val minBuf = AudioRecord.getMinBufferSize(
                        16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                    ).coerceAtLeast(3200)
                    val rec = AudioRecord(
                        MediaRecorder.AudioSource.MIC, 16000,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
                    )
                    rec.startRecording()
                    runOnUiThread { status.text = getString(R.string.follow_on) }
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
                } else {
                    // 模拟跟随：按真实 100ms 节奏把 WAV 帧发进同一条管线，耗尽自动停
                    runOnUiThread { status.text = "模拟跟随 · 播放测试语音中" }
                    val sp = simPcm!!
                    val buf = ShortArray(1600) // 100ms @16kHz
                    while (pumping && simPos < sp.size) {
                        val n = minOf(buf.size, sp.size - simPos)
                        System.arraycopy(sp, simPos, buf, 0, n)
                        simPos += n
                        val bb = java.nio.ByteBuffer.allocate(n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        bb.asShortBuffer().put(buf, 0, n)
                        sendBroadcast(
                            Intent(FollowIpc.ACTION_PCM).setPackage(packageName)
                                .putExtra(FollowIpc.EXTRA_PCM, bb.array())
                        )
                        Thread.sleep(100)
                    }
                    simPcm = null
                    simPos = 0
                    runOnUiThread {
                        if (followOn) {
                            status.text = "模拟语音播完 · 跟随已自动停止"
                            stopFollow()
                            btnFollow.setTextColor(0xFFFFFFFF.toInt())
                        }
                    }
                }
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

    private fun stopFollowInternal() {
        followOn = false
        controller.follow = false
        stopMicPump()
        uiReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        uiReceiver = null
        handler.removeCallbacks(watchdog)
        try { stopService(Intent(this, FollowService::class.java)) } catch (_: Exception) {}
    }

    /** 识别进程 12 秒无消息视为已被 exit 杀死：提示 + 复位，可重试 */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!followOn) return
            if (System.currentTimeMillis() - lastAlive > 12000) {
                status.text = "识别引擎异常退出，请重新点「跟随」"
                stopFollowInternal()
                btnFollow.setTextColor(0xFFFFFFFF.toInt())
                return
            }
            handler.postDelayed(this, 3000)
        }
    }

    /** 跟随模式：念到的当前句整句琥珀高亮（和悬浮窗一致的视觉） */
    private fun applyFollowHighlight() {
        val content = script?.content ?: return
        if (content.isEmpty()) return
        val sb = SpannableStringBuilder(content)
        val offset = engine.originalOffset().coerceIn(0, content.length - 1)
        val stops = charArrayOf('。', '！', '？', '!', '?', '\n', '…', '；')
        var from = offset
        while (from > 0 && stops.none { content[from - 1] == it }) from--
        var to = offset
        while (to < content.length && stops.none { content[to] == it }) to++
        if (to < content.length) to++
        val end = to.coerceAtMost(content.length)
        sb.setSpan(BackgroundColorSpan(0x22FFD54D), from, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(0xFFFFD54D.toInt()), from, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        scriptText.text = sb
    }

    private fun stopFollow() {
        stopFollowInternal()
        btnFollow.setTextColor(0xFFFFFFFF.toInt())
        status.text = ""
        script?.let { scriptText.text = it.content } // 撤掉高亮恢复纯文本
    }

    private fun launchOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.perm_title)
                .setMessage(R.string.perm_steps)
                .setPositiveButton(R.string.perm_go_overlay) { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                }
                .setNeutralButton(R.string.perm_go_details) { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    )
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        stopFollow() // 避免两个界面同时占麦克风
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, OverlayService::class.java)
                .putExtra("id", script!!.id)
        )
        finish()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startFollow()
            else status.text = getString(R.string.need_mic)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        controller.release()
        stopFollowInternal()
    }

    companion object {
        const val PREFS = "ticiqi"
        const val KEY_FONT = "font"
        const val KEY_SPEED = "speed"
        const val KEY_OPACITY = "opacity"
        const val KEY_WINH = "winh"
        private const val REQ_MIC = 1
        private const val REQ_NOTIF = 2
    }
}
