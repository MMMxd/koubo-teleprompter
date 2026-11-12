package com.mxd.koubo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 稿件 */
data class DraftItem(
    val id: Long,
    var title: String,
    var personaName: String,
    var platform: String,
    var subject: String,
    var content: String,
    var updatedAt: Long
)

object DraftStore {

    private fun file(ctx: Context) = File(ctx.filesDir, "drafts.json")

    fun load(ctx: Context): MutableList<DraftItem> {
        val f = file(ctx)
        val out = mutableListOf<DraftItem>()
        if (!f.exists()) return out
        return try {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    DraftItem(
                        o.getLong("id"),
                        o.getString("title"),
                        o.getString("personaName"),
                        o.getString("platform"),
                        o.getString("subject"),
                        o.getString("content"),
                        o.getLong("updatedAt")
                    )
                )
            }
            out.sortByDescending { it.updatedAt }
            out
        } catch (e: Exception) {
            out
        }
    }

    fun save(ctx: Context, list: List<DraftItem>) {
        val arr = JSONArray()
        for (d in list) {
            val o = JSONObject()
            o.put("id", d.id)
            o.put("title", d.title)
            o.put("personaName", d.personaName)
            o.put("platform", d.platform)
            o.put("subject", d.subject)
            o.put("content", d.content)
            o.put("updatedAt", d.updatedAt)
            arr.put(o)
        }
        file(ctx).writeText(arr.toString(), Charsets.UTF_8)
    }

    fun upsert(ctx: Context, item: DraftItem) {
        val list = load(ctx)
        val idx = list.indexOfFirst { it.id == item.id }
        if (idx >= 0) list[idx] = item else list.add(0, item)
        save(ctx, list)
    }

    fun delete(ctx: Context, id: Long) {
        val list = load(ctx)
        list.removeAll { it.id == id }
        save(ctx, list)
    }

    fun byId(ctx: Context, id: Long): DraftItem? = load(ctx).firstOrNull { it.id == id }
}

/** 自定义角色持久化（预置角色在 Presets 里） */
object PersonaStore {

    private fun file(ctx: Context) = File(ctx.filesDir, "personas.json")

    fun loadCustom(ctx: Context): MutableList<Persona> {
        val f = file(ctx)
        val out = mutableListOf<Persona>()
        if (!f.exists()) return out
        return try {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Persona(
                        o.getString("id"),
                        o.getString("name"),
                        o.optString("category", "自定义"),
                        o.optString("emoji", "✍️"),
                        o.getString("desc"),
                        o.optString("sample", ""),
                        custom = true
                    )
                )
            }
            out
        } catch (e: Exception) {
            out
        }
    }

    fun addCustom(ctx: Context, name: String, desc: String): Persona {
        val p = Persona("c" + System.currentTimeMillis(), name, "自定义", "✍️", desc, "", custom = true)
        val list = loadCustom(ctx)
        list.add(0, p)
        val arr = JSONArray()
        for (x in list) {
            val o = JSONObject()
            o.put("id", x.id)
            o.put("name", x.name)
            o.put("category", x.category)
            o.put("emoji", x.emoji)
            o.put("desc", x.desc)
            o.put("sample", x.sample)
            arr.put(o)
        }
        file(ctx).writeText(arr.toString(), Charsets.UTF_8)
        return p
    }

    fun all(ctx: Context): List<Persona> = loadCustom(ctx) + Presets.PERSONAS
}
