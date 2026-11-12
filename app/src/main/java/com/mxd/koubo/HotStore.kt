package com.mxd.koubo

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 一条热点 */
data class HotItem(
    val source: String,      // douyin/baidu/toutiao/bilibili/weibo
    val title: String,
    val hot: Long,
    val url: String,
    val label: String        // 爆/热/新/沸 等（可能为空）
)

/**
 * 热点榜聚合：五个公开接口，移植自 hotpilot app/collectors/hot.py 的成熟逻辑。
 *  - 单源失败不影响其它源
 *  - 内存缓存 20 分钟
 *  - 百度需要 Referer；全部带桌面 UA
 */
object HotStore {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    private const val CACHE_MS = 20 * 60 * 1000L

    val SOURCES = listOf(
        "douyin" to "抖音",
        "baidu" to "百度",
        "toutiao" to "头条",
        "bilibili" to "B站",
        "weibo" to "微博",
    )

    private var cache: MutableMap<String, List<HotItem>> = mutableMapOf()
    private var errors: MutableMap<String, String> = mutableMapOf()
    private var cacheAt = 0L

    fun isFresh(): Boolean =
        cache.isNotEmpty() && System.currentTimeMillis() - cacheAt < CACHE_MS

    /** 取某个源在缓存里的列表（无则空） */
    fun cacheFor(key: String): List<HotItem> = cache[key].orEmpty()

    /** 并行抓取全部源；onDone 在工作线程回调 */
    fun fetchAll(force: Boolean, onDone: (Map<String, List<HotItem>>, Map<String, String>, Long) -> Unit) {
        if (!force && isFresh()) {
            onDone(cache, errors, cacheAt)
            return
        }
        Thread {
            val results = mutableMapOf<String, List<HotItem>>()
            val errs = mutableMapOf<String, String>()
            val threads = SOURCES.map { (key, _) ->
                Thread {
                    try {
                        results[key] = fetchSource(key)
                    } catch (e: Exception) {
                        errs[key] = e.message ?: "抓取失败"
                    }
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join(15000) }
            cache.clear()
            cache.putAll(results)
            errors = errs
            cacheAt = System.currentTimeMillis()
            onDone(results, errs, cacheAt)
        }.start()
    }

    private fun getJson(url: String, referer: String? = null): JSONObject {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        if (referer != null) b.header("Referer", referer)
        client.newCall(b.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            return JSONObject(text)
        }
    }

    private fun fetchSource(key: String): List<HotItem> = when (key) {
        "weibo" -> weibo()
        "baidu" -> baidu()
        "toutiao" -> toutiao()
        "bilibili" -> bilibili()
        "douyin" -> douyin()
        else -> emptyList()
    }

    /* 微博热搜 */
    private fun weibo(): List<HotItem> {
        val data = getJson("https://weibo.com/ajax/side/hotSearch")
            .optJSONObject("data") ?: return emptyList()
        val out = mutableListOf<HotItem>()
        val arr = data.optJSONArray("realtime") ?: return emptyList()
        for (i in 0 until arr.length().coerceAtMost(30)) {
            val it = arr.optJSONObject(i) ?: continue
            val title = it.optString("word", it.optString("note", ""))
            if (title.isEmpty()) continue
            val label = it.optString("label_name", "")
            out.add(
                HotItem(
                    "weibo", title, it.optLong("num", 0),
                    "https://s.weibo.com/weibo?q=%23${java.net.URLEncoder.encode(title, "UTF-8")}%23",
                    label
                )
            )
        }
        return out
    }

    /* 百度热搜（多层嵌套递归取 word/query） */
    private fun baidu(): List<HotItem> {
        val data = getJson(
            "https://top.baidu.com/api/board?platform=wise&tab=realtime",
            referer = "https://top.baidu.com/board?tab=realtime"
        )
        val out = mutableListOf<HotItem>()
        fun walkAny(node: Any?) {
            when (node) {
                is JSONObject -> {
                    val title = node.optString("word", node.optString("query", ""))
                    if (title.isNotEmpty()) {
                        out.add(
                            HotItem(
                                "baidu", title, node.optLong("hotScore", 0),
                                node.optString("url", ""),
                                if (node.optString("tag", "").isNotEmpty() && node.opt("tag") is String) node.optString("tag") else ""
                            )
                        )
                    }
                    for (key in listOf("content", "cards")) {
                        val child = node.opt(key)
                        if (child is JSONArray) for (i in 0 until child.length()) walkAny(child.opt(i))
                    }
                }
                is JSONArray -> for (i in 0 until node.length()) walkAny(node.opt(i))
            }
        }
        val cards = data.optJSONObject("data")?.optJSONArray("cards") ?: JSONArray()
        for (i in 0 until cards.length()) walkAny(cards.opt(i))
        return out
    }

    /* 头条热榜 */
    private fun toutiao(): List<HotItem> {
        val data = getJson("https://www.toutiao.com/hot-event/hot-board/?origin=toutiao_pc")
        val arr = data.optJSONArray("data") ?: return emptyList()
        val out = mutableListOf<HotItem>()
        for (i in 0 until arr.length().coerceAtMost(30)) {
            val it = arr.optJSONObject(i) ?: continue
            val title = it.optString("Title", "")
            if (title.isEmpty()) continue
            out.add(HotItem("toutiao", title, it.optLong("HotValue", 0), it.optString("Url", ""), ""))
        }
        return out
    }

    /* B站热搜 */
    private fun bilibili(): List<HotItem> {
        val data = getJson("https://api.bilibili.com/x/web-interface/search/square?limit=25")
        if (data.optInt("code", -1) != 0) throw RuntimeException("bilibili code=${data.optInt("code")}")
        val list = data.optJSONObject("data")?.optJSONObject("trending")?.optJSONArray("list")
            ?: return emptyList()
        val out = mutableListOf<HotItem>()
        for (i in 0 until list.length().coerceAtMost(25)) {
            val it = list.optJSONObject(i) ?: continue
            val title = it.optString("keyword", "")
            if (title.isEmpty()) continue
            out.add(
                HotItem(
                    "bilibili", title, it.optLong("hot_score", 0),
                    "https://search.bilibili.com/all?keyword=${java.net.URLEncoder.encode(title, "UTF-8")}",
                    it.optString("icon", "")
                )
            )
        }
        return out
    }

    /* 抖音热搜 */
    private fun douyin(): List<HotItem> {
        val data = getJson("https://www.iesdouyin.com/web/api/v2/hotsearch/billboard/word/")
        val arr = data.optJSONArray("word_list") ?: return emptyList()
        val out = mutableListOf<HotItem>()
        for (i in 0 until arr.length().coerceAtMost(25)) {
            val it = arr.optJSONObject(i) ?: continue
            val title = it.optString("word", "")
            if (title.isEmpty()) continue
            out.add(
                HotItem(
                    "douyin", title, it.optLong("hot_value", 0),
                    "https://www.douyin.com/search/${java.net.URLEncoder.encode(title, "UTF-8")}",
                    it.optString("label", "")
                )
            )
        }
        return out
    }
}
