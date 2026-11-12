package com.mxd.koubo

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 热点榜：五源聚合，单源失败不影响其它源；点「写稿」把热点带进创作简报 */
class HotActivity : AppCompatActivity() {

    private lateinit var srcRow: LinearLayout
    private lateinit var status: TextView
    private lateinit var adapter: HotAdapter
    private var activeSource = "douyin"
    private var loading = false
    private val fmt = SimpleDateFormat("HH:mm", Locale.CHINA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hot)

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        adapter = HotAdapter()
        list.adapter = adapter
        status = findViewById(R.id.hotStatus)
        srcRow = findViewById(R.id.srcRow)

        buildSrcRow()
        findViewById<View>(R.id.btnRefresh).setOnClickListener { load(force = true) }
        findViewById<View>(R.id.tabBackDrafts).setOnClickListener { finish() }
        findViewById<View>(R.id.tabBackMine).setOnClickListener { finish() }
        findViewById<View>(R.id.tabOpenWrite).setOnClickListener {
            startActivity(Intent(this@HotActivity, ComposeActivity::class.java))
        }
        findViewById<View>(R.id.list).visibility = View.VISIBLE

        load(force = false)
    }

    private fun buildSrcRow() {
        srcRow.removeAllViews()
        HotStore.SOURCES.forEachIndexed { i, (key, label) ->
            val tv = TextView(this)
            tv.text = label
            tv.gravity = android.view.Gravity.CENTER
            tv.textSize = 13f
            tv.setPadding(0, dp(9), 0, dp(9))
            tv.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = if (i < HotStore.SOURCES.size - 1) dp(8) else 0
            }
            markSrc(tv, key == activeSource)
            tv.setOnClickListener {
                activeSource = key
                buildSrcRow()
                render()
            }
            srcRow.addView(tv)
        }
    }

    private fun markSrc(v: TextView, on: Boolean) {
        if (on) {
            v.setBackgroundResource(R.drawable.bg_tile_tp)
            v.setTextColor(getColor(R.color.primary))
            v.setTypeface(null, android.graphics.Typeface.BOLD)
        } else {
            v.setBackgroundResource(R.drawable.bg_input)
            v.setTextColor(getColor(R.color.text_sub))
            v.setTypeface(null, android.graphics.Typeface.NORMAL)
        }
    }

    private fun load(force: Boolean) {
        if (loading) return
        loading = true
        status.text = getString(R.string.hot_loading)
        status.visibility = View.VISIBLE
        HotStore.fetchAll(force) { results, errs, at ->
            runOnUiThread {
                loading = false
                adapter.items = results[activeSource].orEmpty()
                adapter.notifyDataSetChanged()
                val errKeys = errs.keys.filter { results[it].isNullOrEmpty() }
                val time = "更新于 ${fmt.format(Date(at))} · ${adapter.items.size} 条"
                status.text = if (adapter.items.isEmpty() && errKeys.isNotEmpty())
                    "这个源暂时没抓到（${errKeys.joinToString("、")}），换一个源或点右上角刷新"
                else if (adapter.items.isEmpty())
                    "这个源暂时没抓到数据，换一个源或点右上角刷新"
                else time
            }
        }
    }

    private fun render() {
        adapter.items = HotStore.cacheFor(activeSource)
        adapter.notifyDataSetChanged()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    inner class HotAdapter : RecyclerView.Adapter<HotVH>() {
        var items: List<HotItem> = emptyList()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HotVH =
            HotVH(LayoutInflater.from(parent.context).inflate(R.layout.item_hot, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: HotVH, position: Int) {
            val item = items[position]
            val rank = position + 1
            h.rank.text = rank.toString()
            h.rank.setTextColor(
                when (rank) {
                    1 -> 0xFFFF2442.toInt()
                    2 -> 0xFFFF7A00.toInt()
                    3 -> 0xFFFFB020.toInt()
                    else -> getColor(R.color.text_sub)
                }
            )
            h.title.text = item.title
            val hotText = if (item.hot > 0) {
                if (item.hot >= 10000) "${"%.1f".format(item.hot / 10000.0)}万" else "${item.hot}"
            } else ""
            h.hot.text = hotText
            if (item.label.isNotBlank() && item.label.length <= 2) {
                h.label.visibility = View.VISIBLE
                h.label.text = item.label
            } else {
                h.label.visibility = View.GONE
            }
            h.write.setOnClickListener {
                startActivity(
                    Intent(this@HotActivity, ComposeActivity::class.java)
                        .putExtra("hotspot", item.title)
                )
            }
            h.itemView.setOnLongClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
                } catch (_: Exception) {
                    Toast.makeText(this@HotActivity, "没有可打开的浏览器", Toast.LENGTH_SHORT).show()
                }
                true
            }
        }
    }

    class HotVH(v: View) : RecyclerView.ViewHolder(v) {
        val rank: TextView = v.findViewById(R.id.rank)
        val title: TextView = v.findViewById(R.id.hTitle)
        val label: TextView = v.findViewById(R.id.hLabel)
        val hot: TextView = v.findViewById(R.id.hHot)
        val write: TextView = v.findViewById(R.id.btnWrite)
    }
}
