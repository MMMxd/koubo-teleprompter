package com.mxd.koubo

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 首页宿主：底部四 Tab —— 稿件（库）/ 热点（找选题）/ 写稿（AI 帮写 或 自己粘）/ 我的（偏好接口）
 * 稿件、写稿、我的三个视图在本 Activity 内切换；热点是独立页面。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var drafts: MutableList<DraftItem>
    private lateinit var adapter: DraftAdapter
    private lateinit var empty: TextView
    private lateinit var statsBar: TextView
    private lateinit var viewDrafts: View
    private lateinit var viewWrite: View
    private lateinit var viewMine: View
    private lateinit var tabDraftsLabel: TextView
    private lateinit var tabWriteLabel: TextView
    private lateinit var tabMineLabel: TextView
    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        showCrashReportIfAny()
        drafts = DraftStore.load(this)

        val list = findViewById<RecyclerView>(R.id.list)
        empty = findViewById(R.id.empty)
        statsBar = findViewById(R.id.statsBar)
        viewDrafts = findViewById(R.id.viewDrafts)
        viewWrite = findViewById(R.id.viewWrite)
        viewMine = findViewById(R.id.viewMine)
        tabDraftsLabel = findViewById(R.id.tabDraftsLabel)
        tabWriteLabel = findViewById(R.id.tabWriteLabel)
        tabMineLabel = findViewById(R.id.tabMineLabel)

        list.layoutManager = LinearLayoutManager(this)
        adapter = DraftAdapter()
        list.adapter = adapter

        // Tab 切换
        findViewById<View>(R.id.tabDrafts).setOnClickListener { switchTab(TAB_DRAFTS) }
        findViewById<View>(R.id.tabHot).setOnClickListener {
            startActivity(Intent(this, HotActivity::class.java))
        }
        findViewById<View>(R.id.tabWrite).setOnClickListener {
            // ＋ 是动作按钮：直接打开写稿页，不再作为导航 Tab
            startActivity(Intent(this, ComposeActivity::class.java))
        }
        findViewById<View>(R.id.tabMine).setOnClickListener { switchTab(TAB_MINE) }
        findViewById<View>(R.id.btnSettings).setOnClickListener { switchTab(TAB_MINE) }

        // 写稿 Hub 三个入口
        findViewById<View>(R.id.entryAi).setOnClickListener {
            startActivity(Intent(this, ComposeActivity::class.java))
        }
        findViewById<View>(R.id.entryMy).setOnClickListener {
            startActivity(Intent(this, ManualEditActivity::class.java))
        }
        findViewById<View>(R.id.entryHot).setOnClickListener {
            startActivity(Intent(this, HotActivity::class.java))
        }

        bindMinePane()
        switchTab(TAB_DRAFTS)
        refresh()
    }

    private fun switchTab(tab: Int) {
        viewDrafts.visibility = if (tab == TAB_DRAFTS) View.VISIBLE else View.GONE
        viewMine.visibility = if (tab == TAB_MINE) View.VISIBLE else View.GONE
        markTab(tabDraftsLabel, tab == TAB_DRAFTS)
        markTab(tabMineLabel, tab == TAB_MINE)
    }

    private fun markTab(v: TextView, on: Boolean) {
        v.setTextColor(getColor(if (on) R.color.primary else R.color.text_sub))
        v.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

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
        statsBar.visibility = if (drafts.isEmpty()) View.GONE else View.VISIBLE
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }
        val week = drafts.count { it.updatedAt >= cal.timeInMillis }
        statsBar.text = getString(R.string.stats_fmt, drafts.size, week)
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
        val preview: TextView = v.findViewById(R.id.preview)
        val quickTp: TextView = v.findViewById(R.id.btnQuickTp)
    }

    companion object {
        private const val TAB_DRAFTS = 0
        private const val TAB_WRITE = 1
        private const val TAB_MINE = 2
    }
}
