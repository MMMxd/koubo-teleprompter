package com.mxd.koubo

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 首页宿主：底部三 Tab —— 稿件（库）/ 热点（找选题）/ 我的（偏好接口）
 * 三页全部内嵌本 Activity 原位切换（淡入淡出过渡，滚动位置保留，不再整页跳变）；
 * 写稿走常驻悬浮按钮（＋写稿），任意 Tab 一键直达。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var drafts: MutableList<DraftItem>
    private lateinit var adapter: DraftAdapter
    private lateinit var empty: TextView
    private lateinit var dashLine1: TextView
    private lateinit var dashStat1: TextView
    private lateinit var dashStat1Label: TextView
    private lateinit var dashStat2: TextView
    private lateinit var dashStat2Label: TextView
    private lateinit var viewDrafts: View
    private lateinit var viewHot: View
    private lateinit var viewMine: View
    private lateinit var tabDraftsLabel: TextView
    private lateinit var tabHotLabel: TextView
    private lateinit var tabMineLabel: TextView
    private lateinit var tabDraftsIcon: TextView
    private lateinit var tabHotIcon: TextView
    private lateinit var tabMineIcon: TextView
    private lateinit var hot: HotPane
    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    private var currentTab = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        showCrashReportIfAny()
        drafts = DraftStore.load(this)

        val list = findViewById<RecyclerView>(R.id.list)
        empty = findViewById(R.id.empty)
        dashLine1 = findViewById(R.id.dashLine1)
        dashStat1 = findViewById(R.id.dashStat1)
        dashStat1Label = findViewById(R.id.dashStat1Label)
        dashStat2 = findViewById(R.id.dashStat2)
        dashStat2Label = findViewById(R.id.dashStat2Label)
        viewDrafts = findViewById(R.id.viewDrafts)
        viewHot = findViewById(R.id.viewHot)
        viewMine = findViewById(R.id.viewMine)
        tabDraftsLabel = findViewById(R.id.tabDraftsLabel)
        tabHotLabel = findViewById(R.id.tabHotLabel)
        tabMineLabel = findViewById(R.id.tabMineLabel)
        tabDraftsIcon = findViewById(R.id.tabDraftsIcon)
        tabHotIcon = findViewById(R.id.tabHotIcon)
        tabMineIcon = findViewById(R.id.tabMineIcon)
        hot = HotPane(this, viewHot)

        list.layoutManager = LinearLayoutManager(this)
        adapter = DraftAdapter()
        list.adapter = adapter

        // Tab 切换：全部原位过渡，不再跳页
        findViewById<View>(R.id.tabDrafts).setOnClickListener { switchTab(TAB_DRAFTS) }
        findViewById<View>(R.id.tabHot).setOnClickListener { switchTab(TAB_HOT) }
        findViewById<View>(R.id.tabMine).setOnClickListener { switchTab(TAB_MINE) }
        findViewById<View>(R.id.btnSettings).setOnClickListener { switchTab(TAB_MINE) }

        // 悬浮写稿按钮：点一下直接进写稿；长按走手动写
        val fab = findViewById<View>(R.id.fabWrite)
        fab.setOnClickListener { openCompose() }
        fab.setOnLongClickListener { openManual(); true }

        // 稿件页头部：语音速记 + 手动写入口
        findViewById<View>(R.id.btnMemo).setOnClickListener {
            startActivity(Intent(this, VoiceMemoActivity::class.java))
        }
        findViewById<View>(R.id.btnManual).setOnClickListener { openManual() }

        bindMinePane()
        switchTab(TAB_DRAFTS)
        refresh()
    }

    private fun openCompose() {
        startActivity(Intent(this, ComposeActivity::class.java))
    }

    private fun openManual() {
        startActivity(Intent(this, ManualEditActivity::class.java))
    }

    private fun paneFor(tab: Int): View = when (tab) {
        TAB_HOT -> viewHot
        TAB_MINE -> viewMine
        else -> viewDrafts
    }

    /** 原位切 Tab：旧页淡出、新页淡入并轻微上浮；视图常驻，滚动位置不丢 */
    private fun switchTab(target: Int) {
        if (target == currentTab) return
        val first = currentTab == -1
        val outTab = currentTab
        val out = if (first) null else paneFor(outTab)
        val inn = paneFor(target)
        currentTab = target

        markTab(tabDraftsLabel, tabDraftsIcon, target == TAB_DRAFTS)
        markTab(tabHotLabel, tabHotIcon, target == TAB_HOT)
        markTab(tabMineLabel, tabMineIcon, target == TAB_MINE)
        if (target == TAB_HOT) hot.onShown()

        // 既不入场也不出场的窗格：立即收起并复位（快速连点后不残留半透明状态）
        for (t in intArrayOf(TAB_DRAFTS, TAB_HOT, TAB_MINE)) {
            val p = paneFor(t)
            if (p !== inn && p !== out) {
                p.animate().cancel()
                p.visibility = View.GONE
                p.alpha = 1f
                p.translationY = 0f
            }
        }

        if (first) {
            inn.visibility = View.VISIBLE
            return
        }

        // 出场：淡出后收起（若期间又被选回，endAction 自会失效）
        out?.let { o ->
            o.animate().cancel()
            o.translationY = 0f
            o.animate().alpha(0f).setDuration(140).withEndAction {
                if (currentTab != outTab) {
                    o.visibility = View.GONE
                    o.alpha = 1f
                    o.translationY = 0f
                }
            }.start()
        }

        // 入场：淡入 + 轻微上浮
        inn.animate().cancel()
        inn.visibility = View.VISIBLE
        inn.alpha = 0f
        inn.translationY = dp(14).toFloat()
        inn.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
    }

    private fun markTab(label: TextView, icon: TextView, on: Boolean) {
        label.setTextColor(getColor(if (on) R.color.primary else R.color.text_sub))
        label.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        val s = if (on) 1.12f else 1f
        icon.animate().cancel()
        icon.animate().scaleX(s).scaleY(s).setDuration(160)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /* ── 我的页：提词器默认 + 模型接口 ── */

    private fun bindMinePane() {
        val llmPrefs = getSharedPreferences("wenan", MODE_PRIVATE)
        val tpPrefs = getSharedPreferences(PrompterActivity.PREFS, MODE_PRIVATE)

        val fontVal = findViewById<TextView>(R.id.fontVal)
        val speedVal = findViewById<TextView>(R.id.speedVal)
        var font = tpPrefs.getFloat(PrompterActivity.KEY_FONT, 46f)
        var speed = tpPrefs.getFloat(PrompterActivity.KEY_SPEED, 2f)

        fun refreshTp() {
            fontVal.text = "${font.toInt()} sp"
            speedVal.text = String.format("%.1f", speed)
        }
        refreshTp()

        findViewById<View>(R.id.fontMinus).setOnClickListener {
            font = (font - 4f).coerceIn(20f, 90f); tpPrefs.edit().putFloat(PrompterActivity.KEY_FONT, font).apply(); refreshTp()
        }
        findViewById<View>(R.id.fontPlus).setOnClickListener {
            font = (font + 4f).coerceIn(20f, 90f); tpPrefs.edit().putFloat(PrompterActivity.KEY_FONT, font).apply(); refreshTp()
        }
        findViewById<View>(R.id.speedMinus).setOnClickListener {
            speed = (speed - 0.5f).coerceIn(0.5f, 8f); tpPrefs.edit().putFloat(PrompterActivity.KEY_SPEED, speed).apply(); refreshTp()
        }
        findViewById<View>(R.id.speedPlus).setOnClickListener {
            speed = (speed + 0.5f).coerceIn(0.5f, 8f); tpPrefs.edit().putFloat(PrompterActivity.KEY_SPEED, speed).apply(); refreshTp()
        }

        val etBase = findViewById<EditText>(R.id.baseUrl)
        val etKey = findViewById<EditText>(R.id.apiKey)
        val etModel = findViewById<EditText>(R.id.model)
        etBase.setText(llmPrefs.getString("baseUrl", Llm.DEFAULT_BASE_URL))
        etKey.setText(llmPrefs.getString("apiKey", Llm.DEFAULT_KEY))
        etModel.setText(llmPrefs.getString("model", Llm.DEFAULT_MODEL))

        findViewById<View>(R.id.btnSave).setOnClickListener {
            val base = etBase.text.toString().trim()
            val key = etKey.text.toString().trim()
            val model = etModel.text.toString().trim()
            if (base.isEmpty() || key.isEmpty() || model.isEmpty()) {
                Toast.makeText(this, "三项都不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            llmPrefs.edit()
                .putString("baseUrl", base)
                .putString("apiKey", key)
                .putString("model", model)
                .apply()
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        }
    }

    /* ── 稿件列表 ── */

    override fun onResume() {
        super.onResume()
        drafts = DraftStore.load(this)
        refresh()
        // 从写稿/提词页回来时，若停在热点 Tab 也顺手保鲜
        if (currentTab == TAB_HOT) hot.onShown()
    }

    /** 上次闪退的堆栈弹窗展示（诊断用） */
    private fun showCrashReportIfAny() {
        val f = java.io.File(filesDir, "crash.txt")
        if (!f.exists() || f.length() == 0L) return
        val trace = try { f.readText(Charsets.UTF_8) } catch (_: Exception) { return }
        AlertDialog.Builder(this)
            .setTitle("检测到上次启动闪退（请截图发我）")
            .setMessage(trace)
            .setPositiveButton("复制并清除") { _, _ ->
                val cb = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cb.setPrimaryClip(android.content.ClipData.newPlainText("crash", trace))
                f.delete()
            }
            .setNegativeButton("已截图，关闭") { _, _ -> f.delete() }
            .show()
    }

    private fun refresh() {
        adapter.notifyDataSetChanged()
        empty.visibility = if (drafts.isEmpty()) View.VISIBLE else View.GONE
        refreshDashboard()
    }

    /** 创作驾驶舱：本周产线 + 播放量杠杆指标（5秒完播均值 / 钩子均分） */
    private fun refreshDashboard() {
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }
        val weekAt = cal.timeInMillis
        val weekDrafts = drafts.count { it.updatedAt >= weekAt }
        val weekPubs = drafts.sumOf { d -> d.publishes.count { it.at >= weekAt } }
        dashLine1.text = "本周稿件 +$weekDrafts · 发布 $weekPubs 场"

        val retains = drafts.flatMap { it.publishes }.filter { it.retain5s >= 0 }
        if (retains.isEmpty()) {
            dashStat1.text = "—"
            dashStat1Label.text = "5秒完播均值 · 记一场发布就有"
        } else {
            dashStat1.text = "${retains.map { it.retain5s }.average().toInt()}%"
            dashStat1Label.text = "5秒完播均值 · ${retains.size} 场数据"
        }

        val hooks = drafts.filter { it.hookScore >= 0 }.map { it.hookScore }
        if (hooks.isEmpty()) {
            dashStat2.text = "—"
            dashStat2Label.text = "钩子均分 · 去钩子工坊评估"
        } else {
            dashStat2.text = "${hooks.average().toInt()}"
            dashStat2Label.text = "钩子均分 · ${hooks.size} 篇已评估"
        }
    }

    inner class DraftAdapter : RecyclerView.Adapter<DraftVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DraftVH =
            DraftVH(LayoutInflater.from(parent.context).inflate(R.layout.item_draft, parent, false))

        override fun getItemCount(): Int = drafts.size

        override fun onBindViewHolder(h: DraftVH, position: Int) {
            val d = drafts[position]
            h.title.text = d.title.ifBlank { d.subject }
            h.tagPersona.text = d.personaName
            h.tagPlatform.text = d.platform
            h.tagWords.text = "${d.content.length} 字"
            h.preview.text = d.content.replace(Regex("#+\\s*"), "").replace('\n', ' ')

            // 播放量杠杆行：钩子强度 / 预计时长 / 发布场次
            if (d.hookScore >= 0) {
                h.tagHook.visibility = View.VISIBLE
                h.tagHook.setBackgroundResource(Workshops.scoreBg(d.hookScore))
                h.tagHook.text = "钩子 ${d.hookScore}"
            } else {
                h.tagHook.visibility = View.GONE
            }
            if (d.content.length >= 80) {
                val sec = (d.content.length / 4.5).toInt()
                h.tagDur.visibility = View.VISIBLE
                h.tagDur.text = "≈${sec / 60}分%02d秒".format(sec % 60)
            } else {
                h.tagDur.visibility = View.GONE
            }
            if (d.publishes.isNotEmpty()) {
                h.tagPub.visibility = View.VISIBLE
                h.tagPub.text = "已发 ${d.publishes.size} 场"
            } else {
                h.tagPub.visibility = View.GONE
            }

            h.quickTp.setOnClickListener {
                startActivity(Intent(this@MainActivity, PrompterActivity::class.java).putExtra("id", d.id))
            }
            h.itemView.setOnClickListener {
                startActivity(Intent(this@MainActivity, DraftActivity::class.java).putExtra("id", d.id))
            }
        }
    }

    class DraftVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.title)
        val tagPersona: TextView = v.findViewById(R.id.tagPersona)
        val tagPlatform: TextView = v.findViewById(R.id.tagPlatform)
        val tagWords: TextView = v.findViewById(R.id.tagWords)
        val tagHook: TextView = v.findViewById(R.id.tagHook)
        val tagDur: TextView = v.findViewById(R.id.tagDur)
        val tagPub: TextView = v.findViewById(R.id.tagPub)
        val preview: TextView = v.findViewById(R.id.preview)
        val quickTp: TextView = v.findViewById(R.id.btnQuickTp)
    }

    companion object {
        private const val TAB_DRAFTS = 0
        private const val TAB_HOT = 1
        private const val TAB_MINE = 2
    }
}
