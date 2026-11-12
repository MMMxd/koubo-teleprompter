package com.mxd.koubo

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.Choreographer
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 滚动控制器：全屏提词页和悬浮窗共用。
 *
 *  - 匀速模式：speedCps（字/秒）× 行高 = 每秒滚动像素
 *  - 跟随模式：引擎给出目标字，换算目标 Y，按比例追赶（落后越多滚越快）
 *  - 阅读线固定在可视区 28% 高度处
 */
class PrompterController(
    private val scroll: ScrollView,
    private val text: TextView,
    var engine: FollowEngine? = null
) : Choreographer.FrameCallback {

    var playing = false
        private set
    var follow = false
    var speedCps = 2.0f                     // 每秒滚过几行行高
    var onEnded: (() -> Unit)? = null       // 滚到底自动停
    var onTick: (() -> Unit)? = null

    private var lastFrameNs = 0L
    private val choreographer = Choreographer.getInstance()

    fun play() {
        if (playing) return
        playing = true
        lastFrameNs = 0
        choreographer.postFrameCallback(this)
        onTick?.invoke()
    }

    fun pause() {
        playing = false
        choreographer.removeFrameCallback(this)
        onTick?.invoke()
    }

    fun toggle() = if (playing) pause() else play()

    fun seekToStart() {
        scroll.scrollY = 0
        engine?.reset()
    }

    /** 让当前字所在行滚到阅读线位置，并按此初始化匀速起点 */
    fun snapToEngine() {
        val e = engine ?: return
        scroll.scrollY = targetY(e.originalOffset())
    }

    override fun doFrame(ns: Long) {
        if (!playing) return
        if (lastFrameNs == 0L) lastFrameNs = ns
        val dt = ((ns - lastFrameNs) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.05f)
        lastFrameNs = ns

        val maxScroll = max(text.height - scroll.height, 0)
        val lineH = text.lineHeight.coerceAtLeast(1)
        val cur = scroll.scrollY

        val dy: Int = if (follow && engine != null) {
            val diff = targetY(engine!!.originalOffset()) - cur
            when {
                diff > 0 -> min(diff, max(speedCps * lineH * dt, diff * dt * 3f).toInt().coerceAtLeast(1))
                diff < 0 -> max(diff, (diff * dt * 2f).toInt().coerceAtMost(-1))
                else -> 0
            }
        } else {
            (speedCps * lineH * dt).toInt().coerceAtLeast(if (speedCps > 0f) 1 else 0)
        }

        if (dy != 0) scroll.scrollTo(cur, (cur + dy).coerceIn(0, maxScroll))

        if (scroll.scrollY >= maxScroll && maxScroll > 0) {
            pause()
            onEnded?.invoke()
            return
        }
        choreographer.postFrameCallback(this)
    }

    /** 原文偏移 -> 应该滚到的 Y 像素（让该行顶到阅读线） */
    private fun targetY(offset: Int): Int {
        val layout = text.layout ?: return scroll.scrollY
        val line = layout.getLineForOffset(offset)
        val readY = (scroll.height * READ_LINE_RATIO).toInt()
        val y = layout.getLineTop(line) - readY
        val maxScroll = max(text.height - scroll.height, 0)
        return y.coerceIn(0, maxScroll)
    }

    fun release() {
        playing = false
        choreographer.removeFrameCallback(this)
    }

    companion object {
        const val READ_LINE_RATIO = 0.28f

        /** 滚动区被手指碰到时暂停，松手自动恢复（无论匀速还是跟随），手动调整不打断播放 */
        fun bindTouchPause(scroll: ScrollView, controller: PrompterController) {
            scroll.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> controller.pause()
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> controller.play()
                }
                false
            }
        }
    }
}
