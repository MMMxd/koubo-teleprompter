package com.mxd.koubo

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.widget.TextView

/**
 * 兼容胶囊开关：Android16 上 androidx SwitchCompat 测量崩溃
 * （StaticLayout NPE）的替代品。纯 TextView 实现，零系统兼容风险。
 */
class PillToggle(
    context: Context,
    private val offLabel: String,
    private val onLabel: String,
    initialState: Boolean = false,
    private val onToggle: (Boolean) -> Unit
) {
    var isOn: Boolean = initialState
        private set

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()
    private fun dpf(v: Float): Float = v * density

    val view: TextView = TextView(context).apply {
        textSize = 12f
        setPadding(dp(14), dp(7), dp(14), dp(7))
        background = GradientDrawable().apply { cornerRadius = dpf(999f) }
        isClickable = true
        setOnClickListener { set(!isOn) }
    }

    init {
        render()   // 必须等 view 赋值完成后再渲染（apply 块内 view 尚为 null）
    }

    /** 用户点击切换（触发回调） */
    fun set(on: Boolean) {
        if (on == isOn) return
        isOn = on
        render()
        onToggle(on)
    }

    /** 程序化同步状态（不触发回调），供跟随/滚动的自动同步用 */
    fun setSilent(on: Boolean) {
        isOn = on
        render()
    }

    private fun render() {
        view.text = if (isOn) onLabel else offLabel
        view.setTextColor(if (isOn) 0xFFFFFFFF.toInt() else 0xFFCFCFD8.toInt())
        (view.background as GradientDrawable).setColor(if (isOn) 0xFF6C5CE7.toInt() else 0x2EFFFFFF)
    }
}
