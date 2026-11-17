package com.mxd.koubo

import android.app.Activity
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * 播放量杠杆工坊：钩子工坊 / 标题封面工坊 / 发布台账。
 * 钩子与标题封面对应 docs/104 要素权重的 #3（前3-5秒钩子）与 #2（封面+标题）；
 * 发布台账是数据回流的第一步（手动记账，永不自动发布）。
 */
object Workshops {

    /** 评分 → 徽章底色资源（≥75 绿 / ≥50 琥珀 / 其余红） */
    fun scoreBg(score: Int): Int = when {
        score >= 75 -> R.drawable.bg_score_hi
        score >= 50 -> R.drawable.bg_score_mid
        else -> R.drawable.bg_score_lo
    }

    fun verdictStyle(verdict: String): Pair<Int, Int> = when {
        verdict.contains("值得") -> R.drawable.bg_verdict_ok to R.color.score_hi
        verdict.contains("不建议") -> R.drawable.bg_verdict_no to R.color.score_lo
        else -> R.drawable.bg_verdict_mid to Color.parseColor("#FFB45309")
    }

    /* ── ⚡ 钩子工坊 ── */

    fun showHook(activity: Activity, draftId: Long, onApplied: () -> Unit) {
        val draft = DraftStore.byId(activity, draftId) ?: return
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_hook, null)
        val status = view.findViewById<TextView>(R.id.tvStatus)
        val list = view.findViewById<LinearLayout>(R.id.llHookList)

        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setNegativeButton("关闭", null)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()

        Thread {
            try {
                val sys = """
                    你是短视频口播钩子专家。钩子是视频前3-5秒的第一句话，直接决定5秒完播率（播放量的第一张门票）。
                    可用公式库：
                    ①数字冲击（具体数字开场：3个中转站/600块/48小时）
                    ②反常识（颠覆默认认知：越X越Y）
                    ③悬念缺口（说一半留一半，但必须与正文兑现）
                    ④身份点名（对目标人群喊话：做自媒体的注意）
                    ⑤损失厌恶（不做就亏：还在这样…难怪你…）
                    ⑥强对比冲突（我以为…结果…）
                    ⑦直给利益（看完你能拿到什么）
                    ⑧场景奇观（反常画面/行为描述）
                    评估四维：具体性（数字/专有名词）、目标人群相关度、悬念强度、口语顺滑度（念出来不拗口）。
                """.trimIndent()
                val user = """
                    平台：${draft.platform}
                    账号人设：${draft.personaName}
                    稿件开头：${draft.content.take(300)}
                    产出3条替代钩子：每条≤30字、口语化、具体不空泛、必须与稿件内容匹配（不许标题党空头支票）。
                    输出JSON：{"hooks":[{"text":"钩子原文","score":0到100整数,"formula":"公式名","reason":"一句话说明为什么能留住人"}]}
                """.trimIndent()
                val hooks = WorkshopParser.parseHooks(Llm.extractJson(Llm.ask(activity, sys, user, jsonMode = true)))
                if (activity.isFinishing) return@Thread
                activity.runOnUiThread {
                    if (hooks.isEmpty()) {
                        status.text = "模型没给出可用钩子，关掉再试一次"
                        return@runOnUiThread
                    }
                    status.text = "按评分排序 · 点「用这条」替换稿件开头"
                    hooks.forEachIndexed { idx, h ->
                        val item = LayoutInflater.from(activity).inflate(R.layout.item_hook, list, false)
                        val score = item.findViewById<TextView>(R.id.hScore)
                        score.setBackgroundResource(Workshops.scoreBg(h.score))
                        score.text = "${h.score}"
                        item.findViewById<TextView>(R.id.hFormula).text = h.formula
                        item.findViewById<TextView>(R.id.hText).text = h.text
                        item.findViewById<TextView>(R.id.hReason).text = h.reason
                        if (idx > 0) (item.layoutParams as ViewGroup.MarginLayoutParams).topMargin = dp(activity, 8)
                        item.findViewById<TextView>(R.id.btnUse).setOnClickListener {
                            applyHook(activity, draftId, h)
                            Toast.makeText(activity, "已替换开头 · 钩子强度 ${h.score}", Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                            onApplied()
                        }
                        list.addView(item)
                    }
                }
            } catch (e: Exception) {
                if (activity.isFinishing) return@Thread
                activity.runOnUiThread { status.text = "出错了：${e.message?.take(120)}" }
            }
        }.start()
    }

    /** 用新钩子替换稿件第一段（空行分段；无分段则加在开头） */
    private fun applyHook(activity: Activity, draftId: Long, h: HookVariant) {
        val list = DraftStore.load(activity)
        val d = list.firstOrNull { it.id == draftId } ?: return
        val content = d.content
        d.content = if (content.contains("\n\n")) {
            val rest = content.substringAfter("\n\n", "")
            if (rest.isNotBlank()) "${h.text}\n\n$rest" else h.text
        } else {
            "${h.text}\n\n$content"
        }
        d.hookText = h.text
        d.hookScore = maxOf(d.hookScore, h.score)
        d.updatedAt = System.currentTimeMillis()
        DraftStore.save(activity, list)
    }

    /* ── 🎯 标题·封面工坊 ── */

    fun showTitle(activity: Activity, draftId: Long, onApplied: () -> Unit) {
        val draft = DraftStore.byId(activity, draftId) ?: return
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_title, null)
        val status = view.findViewById<TextView>(R.id.tvStatus)
        val list = view.findViewById<LinearLayout>(R.id.llTitleList)

        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setNegativeButton("关闭", null)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()

        Thread {
            try {
                val sys = """
                    你是短视频标题与封面文案专家。标题（视频标题）规则：≤20字、含具体数字或专有名词、口语化、带情绪或悬念、禁用「震惊/惊呆/必看」等廉价感叹词。
                    封面文案成对规则：封面大字=6-12字（封面图上最大的字，名词+数字优先，是截图转发的主角）、封面副文案≤15字（锁定人群或补悬念）。
                    评分=点击潜力：信息量×悬念×人群锁定×与内容的兑现度（标题党空头支票直接低分）。
                """.trimIndent()
                val user = """
                    平台：${draft.platform}
                    账号人设：${draft.personaName}
                    稿件主题：${draft.title}
                    稿件开头：${draft.content.take(300)}
                    产出5组「标题+封面」成对方案，彼此角度不同（悬念/数字/身份/对比/利益各占一条）。
                    输出JSON：{"titles":[{"title":"视频标题","cover":"封面大字","coverSub":"封面副文案","score":0到100整数,"note":"一句话点评"}]}
                """.trimIndent()
                val packs = WorkshopParser.parseTitles(Llm.extractJson(Llm.ask(activity, sys, user, jsonMode = true)))
                if (activity.isFinishing) return@Thread
                activity.runOnUiThread {
                    if (packs.isEmpty()) {
                        status.text = "模型没给出可用方案，关掉再试一次"
                        return@runOnUiThread
                    }
                    status.text = "按点击潜力排序 · 点「采用这套」写入稿件"
                    packs.forEachIndexed { idx, p ->
                        val item = LayoutInflater.from(activity).inflate(R.layout.item_title, list, false)
                        val score = item.findViewById<TextView>(R.id.tScore)
                        score.setBackgroundResource(Workshops.scoreBg(p.score))
                        score.text = "${p.score}"
                        item.findViewById<TextView>(R.id.tNote).text = p.note
                        item.findViewById<TextView>(R.id.tTitle).text = p.title
                        item.findViewById<TextView>(R.id.tCover).text = "封面大字：${p.cover}"
                        item.findViewById<TextView>(R.id.tCoverSub).text = if (p.coverSub.isNotBlank()) "副文案：${p.coverSub}" else ""
                        if (idx > 0) (item.layoutParams as ViewGroup.MarginLayoutParams).topMargin = dp(activity, 8)
                        item.findViewById<TextView>(R.id.btnUse).setOnClickListener {
                            val l2 = DraftStore.load(activity)
                            val d = l2.firstOrNull { it.id == draftId } ?: return@setOnClickListener
                            d.title = p.title
                            d.coverText = p.cover
                            d.coverSub = p.coverSub
                            d.updatedAt = System.currentTimeMillis()
                            DraftStore.save(activity, l2)
                            Toast.makeText(activity, "已采用 · 点击潜力 ${p.score}", Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                            onApplied()
                        }
                        list.addView(item)
                    }
                }
            } catch (e: Exception) {
                if (activity.isFinishing) return@Thread
                activity.runOnUiThread { status.text = "出错了：${e.message?.take(120)}" }
            }
        }.start()
    }

    /* ── 📊 发布台账 ── */

    private val PLATFORMS = listOf("小红书", "抖音", "视频号", "快手", "B站", "公众号")

    fun showPublish(activity: Activity, draftId: Long, onChanged: () -> Unit) {
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_publish, null)
        val chipRow = view.findViewById<LinearLayout>(R.id.chipRow)
        var picked = PLATFORMS[0]

        val chips = mutableListOf<TextView>()
        PLATFORMS.forEachIndexed { i, name ->
            val c = TextView(activity)
            c.text = name
            c.textSize = 12.5f
            c.setPadding(dp(activity, 14), dp(activity, 8), dp(activity, 14), dp(activity, 8))
            c.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = if (i < PLATFORMS.size - 1) dp(activity, 6) else 0
            }
            c.gravity = android.view.Gravity.CENTER
            c.setOnClickListener { picked = name; styleChips(chips, picked, activity) }
            chipRow.addView(c)
            chips.add(c)
        }
        styleChips(chips, picked, activity)

        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setNegativeButton("取消", null)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()

        view.findViewById<TextView>(R.id.btnPubSave).setOnClickListener {
            fun num(id: Int): Int =
                view.findViewById<TextView>(id).text.toString().trim().toIntOrNull() ?: -1
            val rec = PublishRecord(
                id = System.currentTimeMillis(),
                platform = picked,
                at = System.currentTimeMillis(),
                retain5s = num(R.id.etRetain).coerceIn(-1, 100),
                completion = num(R.id.etCompletion).coerceIn(-1, 100),
                likes = num(R.id.etLikes).coerceAtLeast(0),
                comments = num(R.id.etComments).coerceAtLeast(0),
                shares = num(R.id.etShares).coerceAtLeast(0)
            )
            val list = DraftStore.load(activity)
            val d = list.firstOrNull { it.id == draftId }
            if (d != null) {
                d.publishes.add(0, rec)
                d.updatedAt = d.updatedAt // 记账不改稿件排序
                DraftStore.save(activity, list)
            }
            Toast.makeText(activity, "已记入台账 · $picked", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            onChanged()
        }
    }

    private fun styleChips(chips: List<TextView>, picked: String, activity: Activity) {
        chips.forEach { c ->
            val on = c.text.toString() == picked
            c.setBackgroundResource(if (on) R.drawable.bg_tile_tp else R.drawable.bg_pill_off)
            c.setTextColor(activity.getColor(if (on) R.color.primary else R.color.text_sub))
            c.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    /** 发布台账行渲染（DraftActivity 复用） */
    fun renderPublishRows(activity: Activity, container: LinearLayout, records: List<PublishRecord>, fmt: java.text.DateFormat, onDelete: (PublishRecord) -> Unit) {
        container.removeAllViews()
        if (records.isEmpty()) {
            val empty = TextView(activity)
            empty.text = "还没有记录：发布后在这里记一笔，攒出你自己的播放量杠杆数据"
            empty.textSize = 11.5f
            empty.setTextColor(activity.getColor(R.color.text_sub))
            container.addView(empty)
            return
        }
        for (rec in records) {
            val row = LayoutInflater.from(activity).inflate(R.layout.item_publish_row, container, false)
            row.findViewById<TextView>(R.id.pPlatform).text = rec.platform
            row.findViewById<TextView>(R.id.pDate).text = fmt.format(java.util.Date(rec.at))
            val stats = buildString {
                if (rec.retain5s >= 0) append("5s ${rec.retain5s}%")
                if (rec.completion >= 0) { if (isNotEmpty()) append(" · "); append("完播 ${rec.completion}%") }
                if (rec.likes > 0 || rec.comments > 0 || rec.shares > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("赞${rec.likes} 评${rec.comments} 转${rec.shares}")
                }
                if (isEmpty()) append("数据待补")
            }
            row.findViewById<TextView>(R.id.pStats).text = stats
            row.setOnLongClickListener {
                AlertDialog.Builder(activity)
                    .setMessage("删除这条发布记录？")
                    .setPositiveButton("删除") { _, _ -> onDelete(rec) }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
            container.addView(row)
        }
    }

    private fun dp(activity: Activity, v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
}
