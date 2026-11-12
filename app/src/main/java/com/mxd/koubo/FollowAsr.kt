package com.mxd.koubo

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import java.io.File

/**
 * 识别器封装：跑在 :speech 独立进程里。
 * sherpa native 层失败时会直接 exit() 杀掉所在进程——隔离后只死识别进程，
 * 悬浮窗/主界面完全不受影响，用户可一键重试。
 */
class FollowAsr(
    private val context: Context,
    private val onText: (String) -> Unit
) {

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var acc = StringBuilder()
    private var ready = false

    /** 后台线程调用：复制模型到 filesDir + 按路径加载（assets 直读在某些系统上不可靠） */
    fun init(onReady: () -> Unit, onError: (String) -> Unit) {
        try {
            val modelDir = File(context.filesDir, "model").apply { mkdirs() }
            for (name in listOf("model.int8.onnx", "tokens.txt", "bbpe.model")) {
                val dst = File(modelDir, name)
                if (dst.exists() && dst.length() > 0L) continue
                context.assets.open("model/$name").use { input ->
                    dst.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
                }
            }
            val base = modelDir.absolutePath + "/"
            val cfg = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    zipformer2Ctc = OnlineZipformer2CtcModelConfig(model = base + "model.int8.onnx"),
                    tokens = base + "tokens.txt",
                    numThreads = 2,
                    modelingUnit = "bpe",
                    bpeVocab = base + "bbpe.model",
                ),
                enableEndpoint = true,
            )
            // assetManager = null：让 sherpa 按文件路径加载（filesDir 下的模型）
            val r = OnlineRecognizer(assetManager = null, config = cfg)
            stream = r.createStream()
            recognizer = r
            ready = true
            onReady()
        } catch (t: Throwable) {
            onError("模型初始化失败：${t.message}")
        }
    }

    /** 喂入 100ms PCM 帧，内部解码并回调累计文本 */
    fun feed(pcm: ShortArray) {
        val r = recognizer ?: return
        val st = stream ?: return
        if (!ready) return
        val samples = FloatArray(pcm.size) { pcm[it] / 32768f }
        st.acceptWaveform(samples, 16000)
        while (r.isReady(st)) r.decode(st)
        val text = r.getResult(st).text
        if (text.isNotEmpty()) onText(acc.toString() + text)
        if (r.isEndpoint(st)) {
            if (text.isNotBlank()) acc.append(text)
            r.reset(st)
            onText(acc.toString())
        }
    }

    /** 切换稿件时清空累计文本 */
    fun resetText() {
        acc = StringBuilder()
    }

    fun close() {
        try { stream?.release() } catch (_: Exception) {}
        try { recognizer?.release() } catch (_: Exception) {}
        stream = null
        recognizer = null
        acc = StringBuilder()
        ready = false
    }
}
