package com.mxd.koubo

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 选题方向 */
data class Direction(
    val label: String,
    val title: String,
    val angle: String,
    val hook: String,
    val outline: List<String>
)

/**
 * LLM 客户端：直连 OpenAI 兼容端点（zcode · BigModel Coding Plan）。
 *  - ask(): 阻塞一次性调用（选题等结构化输出）
 *  - stream(): 阻塞流式调用（成稿）；onDelta 在网络线程回调，UI 层自行切线程
 * 注意：glm-5.3-flash 是思考模型，思考内容在 reasoning_content 单独字段里，
 * content 才是正文；max_tokens 必须给足（16000），否则思考就把预算烧光。
 */
object Llm {

    const val DEFAULT_BASE_URL = "https://api.deepseek.com"
    const val DEFAULT_MODEL = "deepseek-chat"
    const val DEFAULT_KEY = BuildConfig.LLM_KEY

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun cfg(ctx: Context): Triple<String, String, String> {
        val p = ctx.getSharedPreferences("wenan", Context.MODE_PRIVATE)
        return Triple(
            p.getString("baseUrl", DEFAULT_BASE_URL)!!.trimEnd('/'),
            p.getString("apiKey", DEFAULT_KEY)!!,
            p.getString("model", DEFAULT_MODEL)!!
        )
    }

    private fun buildBody(model: String, system: String, user: String, jsonMode: Boolean, stream: Boolean): JSONObject =
        JSONObject().apply {
            put("model", model)
            put("stream", stream)
            put("temperature", if (jsonMode) 0.6 else 1.0)
            put("top_p", 0.95)
            put("max_tokens", 8192)
            if (jsonMode) put("response_format", JSONObject().put("type", "json_object"))
            put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user))
            )
            if (stream) put("stream_options", JSONObject().put("include_usage", true))
        }

    /** 阻塞一次性调用，返回正文 content */
    fun ask(ctx: Context, system: String, user: String, jsonMode: Boolean): String {
        val (base, key, model) = cfg(ctx)
        val req = Request.Builder()
            .url("$base/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(
                buildBody(model, system, user, jsonMode, stream = false)
                    .toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            )
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("接口返回 ${resp.code}：${text.take(200)}")
            val content = JSONObject(text)
                .optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content").orEmpty()
            if (content.isBlank()) throw RuntimeException("模型把 token 花在思考上、正文为空，请重试或调大 max_tokens")
            return content
        }
    }

    /** 阻塞流式调用，返回完整正文；onDelta 在网络线程逐段回调 */
    fun stream(ctx: Context, system: String, user: String, onDelta: (String) -> Unit): String {
        val (base, key, model) = cfg(ctx)
        val req = Request.Builder()
            .url("$base/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(
                buildBody(model, system, user, jsonMode = false, stream = true)
                    .toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            )
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("接口返回 ${resp.code}：${resp.body?.string()?.take(200)}")
            }
            val source = resp.body!!.source()
            val full = StringBuilder()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.substring(5).trim()
                if (payload.isEmpty() || payload == "[DONE]") continue
                try {
                    val chunk = JSONObject(payload)
                    val delta = chunk.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                    val text = delta.optString("content", "")
                    if (text.isNotEmpty()) {
                        full.append(text)
                        onDelta(text)
                    }
                } catch (_: Exception) {
                    // 心跳等非 JSON 行，忽略
                }
            }
            return full.toString()
        }
    }

    /** 从模型输出里抠出 JSON 对象（容忍代码块标记和前后废话） */
    fun extractJson(text: String): JSONObject {
        val cleaned = text.replace(Regex("^\\s*```(?:json)?"), "").replace(Regex("```\\s*$"), "").trim()
        try {
            return JSONObject(cleaned)
        } catch (_: Exception) {
        }
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start != -1 && end > start) {
            try {
                return JSONObject(cleaned.substring(start, end + 1))
            } catch (_: Exception) {
            }
        }
        throw RuntimeException("模型没有返回可解析的 JSON")
    }

    /** 解析选题方向列表，过滤不完整的项 */
    fun parseDirections(json: JSONObject): List<Direction> {
        val arr = json.optJSONArray("topics") ?: JSONArray()
        val out = mutableListOf<Direction>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title").trim()
            val outline = mutableListOf<String>()
            val oa = o.optJSONArray("outline")
            if (oa != null) for (j in 0 until oa.length()) {
                val s = oa.optString(j).trim()
                if (s.isNotEmpty()) outline.add(s)
            }
            if (title.isEmpty() || outline.isEmpty()) continue
            out.add(
                Direction(
                    label = o.optString("label").trim().take(8),
                    title = title,
                    angle = o.optString("angle").trim(),
                    hook = o.optString("hook").trim(),
                    outline = outline
                )
            )
        }
        return out
    }
}

/* ── v4.7 播放量杠杆工坊：钩子 / 标题封面 / 选题判断 ── */

/** 一条候选钩子（视频前 3-5 秒第一句话） */
data class HookVariant(
    val text: String,
    val score: Int,      // 0~100
    val formula: String, // 使用的钩子公式名
    val reason: String   // 为什么这条能留住人
)

/** 一组标题+封面成对方案 */
data class TitlePack(
    val title: String,     // 视频标题（≤20 字）
    val cover: String,     // 封面大字（6~12 字）
    val coverSub: String,  // 封面副文案（≤15 字）
    val score: Int,        // 点击潜力 0~100
    val note: String       // 一句话点评
)

/** 热点选题判断 */
data class TopicJudge(
    val verdict: String,   // 值得做 / 可以做 / 不建议
    val reason: String,
    val angle: String      // 切角建议
)

object WorkshopParser {

    fun parseHooks(json: JSONObject): List<HookVariant> {
        val arr = json.optJSONArray("hooks") ?: return emptyList()
        val out = mutableListOf<HookVariant>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val text = o.optString("text").trim()
            if (text.isEmpty()) continue
            out.add(
                HookVariant(
                    text = text,
                    score = o.optInt("score", 60).coerceIn(0, 100),
                    formula = o.optString("formula", "钩子").trim().take(10),
                    reason = o.optString("reason").trim()
                )
            )
        }
        return out.sortedByDescending { it.score }
    }

    fun parseTitles(json: JSONObject): List<TitlePack> {
        val arr = json.optJSONArray("titles") ?: return emptyList()
        val out = mutableListOf<TitlePack>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title").trim()
            if (title.isEmpty()) continue
            out.add(
                TitlePack(
                    title = title,
                    cover = o.optString("cover").trim(),
                    coverSub = o.optString("coverSub").trim(),
                    score = o.optInt("score", 60).coerceIn(0, 100),
                    note = o.optString("note").trim()
                )
            )
        }
        return out.sortedByDescending { it.score }
    }

    fun parseJudge(json: JSONObject): TopicJudge =
        TopicJudge(
            verdict = json.optString("verdict", "可以做").trim(),
            reason = json.optString("reason").trim(),
            angle = json.optString("angle").trim()
        )
}
