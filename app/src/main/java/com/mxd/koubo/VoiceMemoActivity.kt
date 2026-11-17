package com.mxd.koubo

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 语音灵感速记：复用 FollowAsr（sherpa-onnx 离线识别），说完存成草稿。
 * 本 Activity 跑在 :speech 独立进程（见 manifest）：sherpa native 失败 exit(255)
 * 只死速记页，主界面/悬浮提词不受影响（与提词跟随同架构纪律）。
 * 模型三件套沿用 PrompterActivity 已复制的 filesDir 副本，二次进入零拷贝。
 */
class VoiceMemoActivity : AppCompatActivity() {

    private lateinit var tvState: TextView
    private lateinit var tvText: TextView
    private lateinit var btnSave: TextView
    private lateinit var btnMic: TextView

    private var asr: FollowAsr? = null
    private var audio: AudioRecord? = null
    private var feedThread: Thread? = null
    @Volatile private var recording = false
    @Volatile private var engineReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_memo)

        tvState = findViewById(R.id.tvState)
        tvText = findViewById(R.id.tvText)
        btnSave = findViewById(R.id.btnSave)
        btnMic = findViewById(R.id.btnMic)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnClear).setOnClickListener {
            asr?.resetText()
            tvText.text = ""
            btnSave.visibility = android.view.View.GONE
        }

        btnSave.setOnClickListener {
            val text = tvText.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            val now = System.currentTimeMillis()
            val title = "灵感速记 " + SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(now))
            DraftStore.upsert(
                this,
                DraftItem(
                    id = now, title = title, personaName = "速记", platform = "通用",
                    subject = title, content = text, updatedAt = now
                )
            )
            Toast.makeText(this, "已存入稿件库 ✓", Toast.LENGTH_SHORT).show()
            finish()
        }

        btnMic.setOnClickListener { toggle() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    private fun toggle() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, R.string.need_mic, Toast.LENGTH_SHORT).show()
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (recording) {
            stopFeed()
            tvState.text = "已停止 · 可以继续说或存为草稿"
            btnMic.text = "🎙"
            return
        }
        recording = true
        btnMic.text = "⏹"
        if (engineReady) {
            startFeed()
            return
        }
        tvState.text = "模型加载中…（首次约 2 秒）"
        val engine = FollowAsr(this) { text ->
            runOnUiThread {
                tvText.text = text
                if (text.isNotBlank()) btnSave.visibility = android.view.View.VISIBLE
            }
        }
        engine.init(
            onReady = {
                engineReady = true
                asr = engine
                if (recording) startFeed()
                runOnUiThread { if (recording) tvState.text = "聆听中 · 再点一下停止" }
            },
            onError = { msg ->
                recording = false
                runOnUiThread {
                    tvState.text = "引擎异常：$msg"
                    btnMic.text = "🎙"
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun startFeed() {
        tvState.text = "聆听中 · 再点一下停止"
        val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audio = AudioRecord(
            MediaRecorder.AudioSource.MIC, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, 16000)
        )
        audio?.startRecording()
        feedThread = Thread {
            val buf = ShortArray(1600) // 100ms @16kHz
            while (recording) {
                val n = audio?.read(buf, 0, buf.size) ?: break
                if (n > 0) asr?.feed(buf.copyOf(n))
            }
        }.apply { start() }
    }

    private fun stopFeed() {
        recording = false
        try { feedThread?.join(500) } catch (_: Exception) {}
        feedThread = null
        try { audio?.stop(); audio?.release() } catch (_: Exception) {}
        audio = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "麦克风已授权，点麦克风开始", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        stopFeed()
        asr?.close()
        asr = null
        super.onDestroy()
    }

    companion object {
        private const val REQ_MIC = 3
    }
}
