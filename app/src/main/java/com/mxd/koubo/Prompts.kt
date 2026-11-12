package com.mxd.koubo

import org.json.JSONObject

/** 提示词组装：角色卡 + 写作规则 + 平台惯例 */
object Prompts {

    private fun personaBlock(p: Persona): String {
        val sb = StringBuilder()
        sb.append("你是一名自媒体创作博主，名字叫「${p.name}」（领域：${p.category}）。\n")
        sb.append("你的定位与写法：${p.desc}\n")
        if (p.sample.isNotBlank()) sb.append("你的标志性开场示例（模仿这种具体、有细节、有态度的口吻）：「${p.sample}」\n")
        return sb.toString()
    }

    private fun rulesBlock(rules: List<Triple<String, String, Boolean>>, extra: String): String {
        val sb = StringBuilder()
        val on = rules.filter { it.third }
        if (on.isNotEmpty()) {
            sb.append("\n写作规则（必须遵守）：\n")
            on.forEachIndexed { i, r -> sb.append("${i + 1}. ${r.first} —— ${r.second}\n") }
        }
        if (extra.isNotBlank()) sb.append("\n本篇的补充要求：$extra\n")
        return sb.toString()
    }

    fun topicsSystem(p: Persona, rules: List<Triple<String, String, Boolean>>, extra: String): String {
        return personaBlock(p) +
            rulesBlock(rules, extra) +
            "\n现在请针对给定题材提出 3 个选题方向。硬性要求：\n" +
            "1. 三个方向的切入角度必须明显不同（例如：亲历实测 / 方法拆解 / 反常识观点 / 对比横评），并在 angle 里写清「这段写什么、另外两个不写什么」；\n" +
            "2. label 是这个差异维度的名字，2~6 个字，三个互不相同；\n" +
            "3. hook 是开篇第一句，必须具体（人、数字、场景），禁止「大家好」式的开场；\n" +
            "4. outline 给 4~5 条内容骨架，每条一句话；\n" +
            "5. 只输出一个 JSON 对象，不要任何解释或代码块标记，格式：\n" +
            "{\"topics\":[{\"label\":\"…\",\"title\":\"…\",\"angle\":\"…\",\"hook\":\"…\",\"outline\":[\"…\",\"…\"]}]}"
    }

    fun topicsUser(subject: String, platform: String, words: Int, hotspot: String = ""): String {
        val base = "题材：$subject\n发布平台：$platform\n目标字数：约 $words 字"
        if (hotspot.isBlank()) return base
        return base +
            "\n\n借势热点：$hotspot\n" +
            "热点使用约束：把它当由头在开头引出，账号自己的观点才是主体，不要通篇围着它转；" +
            "这是榜单标题、没有原文细节，不许编造事件里的人名、数字和时间；不许写「据报道」「有数据显示」这类伪出处表述。"
    }

    fun draftSystem(p: Persona, rules: List<Triple<String, String, Boolean>>, extra: String, platform: String, words: Int): String {
        return personaBlock(p) +
            "\n平台惯例：${Presets.platformGuide(platform)}\n" +
            "目标字数：约 $words 字（上下浮动一成以内）。\n" +
            rulesBlock(rules, extra) +
            "\n输出要求：直接输出正文。第一行是标题（以「# 」开头），之后是正文，不要再输出任何说明、前言或元信息。"
    }

    fun draftUser(subject: String, d: Direction, hotspot: String = ""): String {
        val sb = StringBuilder()
        sb.append("题材：$subject\n\n选定方向：【${d.label}】${d.title}\n")
        if (d.angle.isNotBlank()) sb.append("本篇聚焦：${d.angle}\n")
        if (d.hook.isNotBlank()) sb.append("开篇钩子（要自然融进开头，不要生硬照抄）：${d.hook}\n")
        if (d.outline.isNotEmpty()) {
            sb.append("内容骨架（逐条展开成有细节的段落，不要原样罗列）：\n")
            d.outline.forEachIndexed { i, s -> sb.append("${i + 1}. $s\n") }
        }
        if (hotspot.isNotBlank()) {
            sb.append("\n热点借势：开头用「${hotspot}」自然引出（只当由头）；它是榜单标题、没有细节，不许编造其中的事件、人名、数字。\n")
        }
        return sb.toString()
    }
}
