package com.mxd.koubo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.graphics.Typeface
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class ComposeActivity : AppCompatActivity() {

    private lateinit var personaRow: LinearLayout
    private lateinit var personaDesc: TextView
    private lateinit var platformRow: LinearLayout
    private lateinit var wordsRow: LinearLayout
    private lateinit var rulesRow: LinearLayout
    private lateinit var extraRule: EditText
    private lateinit var subject: EditText
    private lateinit var btnTopics: TextView
    private lateinit var topicStatus: TextView
    private lateinit var btnQuickPrompter: TextView
    private lateinit var btnQuickFloat: TextView
    private lateinit var sn1: TextView
    private lateinit var sn2: TextView
    private lateinit var sn3: TextView
    private lateinit var lbl1: TextView
    private lateinit var lbl2: TextView
    private lateinit var lbl3: TextView
    private lateinit var ln12: View
    private lateinit var ln23: View
    private lateinit var step1: ScrollView
    private lateinit var step2: ScrollView
    private lateinit var step3Scroll: ScrollView
    private lateinit var directionList: LinearLayout
    private lateinit var btnBack: TextView
    private lateinit var stepTitle: TextView
    private lateinit var draftTitle: TextView
    private lateinit var genStatus: TextView
    private lateinit var draftContent: TextView
    private lateinit var dock: LinearLayout
    private lateinit var btnRegenBar: TextView

    private var personas: List<Persona> = emptyList()
    private var personaIdx = 0
    private var platformIdx = 0
    private var wordsIdx = 1
    private val ruleStates = Presets.RULES.map { true }.toMutableList()
    private var directions: List<Direction> = emptyList()
    private var content = ""
    private var savedId = -1L
    private var step = 1
    private var busy = false
    private var hotspot = ""
    private lateinit var hsBanner: LinearLayout
    private lateinit var hsText: TextView
    private lateinit var hsClose: TextView

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_compose)

        personaRow = findViewById(R.id.personaRow)
        personaDesc = findViewById(R.id.personaDesc)
        platformRow = findViewById(R.id.platformRow)
        wordsRow = findViewById(R.id.wordsRow)
        rulesRow = findViewById(R.id.rulesRow)
        extraRule = findViewById(R.id.extraRule)
        subject = findViewById(R.id.subject)
        btnTopics = findViewById(R.id.btnTopics)
        topicStatus = findViewById(R.id.topicStatus)
        btnQuickPrompter = findViewById(R.id.btnQuickPrompter)
        btnQuickFloat = findViewById(R.id.btnQuickFloat)
        sn1 = findViewById(R.id.sn1)
        sn2 = findViewById(R.id.sn2)
        sn3 = findViewById(R.id.sn3)
        lbl1 = findViewById(R.id.lbl1)
        lbl2 = findViewById(R.id.lbl2)
        lbl3 = findViewById(R.id.lbl3)
        ln12 = findViewById(R.id.ln12)
        ln23 = findViewById(R.id.ln23)
        step1 = findViewById(R.id.step1)
        step2 = findViewById(R.id.step2)
        step3Scroll = findViewById(R.id.step3Scroll)
        directionList = findViewById(R.id.directionList)
        btnBack = findViewById(R.id.btnBack)
        stepTitle = findViewById(R.id.stepTitle)
        draftTitle = findViewById(R.id.draftTitle)
        genStatus = findViewById(R.id.genStatus)
        draftContent = findViewById(R.id.draftContent)
        dock = findViewById(R.id.dock)
        btnRegenBar = findViewById(R.id.btnRegenBar)
        hsBanner = findViewById(R.id.hsBanner)
        hsText = findViewById(R.id.hsText)
        hsClose = findViewById(R.id.hsClose)

        hotspot = intent.getStringExtra("hotspot").orEmpty()
        if (hotspot.isNotBlank()) {
            subject.setText(hotspot)
            hsText.text = "借势热点：$hotspot · 只当由头，不编细节，动笔前先核实原文"
            hsBanner.visibility = View.VISIBLE
        }
        hsClose.setOnClickListener {
            hotspot = ""
            hsBanner.visibility = View.GONE
        }

        personas = PersonaStore.all(this)

        buildPersonaRow()
        buildPlatformRow()
        buildWordsRow()
        buildRulesRow()

        btnTopics.setOnClickListener { genTopics() }
        findViewById<TextView>(R.id.btnRegen).setOnClickListener { genTopics() }
        btnQuickPrompter.setOnClickListener { quickFloat() }   // 大按钮 = 悬浮提词（挂到相机上）
        btnQuickFloat.setOnClickListener { quickPrompter() }   // 次级 = App 内全屏
        btnBack.setOnClickListener {
            when (step) {
                2 -> showStep(1)
                3 -> showStep(2)
            }
        }
        findViewById<TextView>(R.id.btnSave).setOnClickListener { save() }
        findViewById<TextView>(R.id.btnShare).setOnClickListener { share() }
        // 系统返回键：第 2/3 步先回上一步，不丢状态
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (step > 1) showStep(step - 1) else finish()
            }
        })
        showStep(1)
    }

    /* ---------- 第 1 步：简报 ---------- */

    private fun buildPersonaRow() {
        personaRow.removeAllViews()
        personas.forEachIndexed { i, p ->
            val tv = TextView(this)
            tv.text = "${p.emoji}\n${p.name}"
            tv.gravity = Gravity.CENTER
            tv.textSize = 11f
            tv.setLineSpacing(0f, 0.9f)
            tv.setPadding(dp(6), dp(8), dp(6), dp(8))
            markSelected(tv, i == personaIdx)
            tv.layoutParams = LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            }
            tv.setOnClickListener {
                personaIdx = i
                refreshPersonaSelection()
                personaDesc.text = buildString {
                    append(p.desc)
                    if (p.sample.isNotBlank()) append("\n\n开场示例：「${p.sample}」")
                }
            }
            personaRow.addView(tv)
        }
        val add = TextView(this)
        add.text = getString(R.string.add_custom_persona)
        add.gravity = Gravity.CENTER
        add.textSize = 12f
        add.setPadding(dp(10), dp(8), dp(10), dp(8))
        add.setBackgroundResource(R.drawable.bg_tile_off)
        add.layoutParams = LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.WRAP_CONTENT)
        add.setOnClickListener { showCustomPersonaDialog() }
        personaRow.addView(add)

        val p = personas[personaIdx.coerceIn(0, personas.size - 1)]
        personaDesc.text = buildString {
            append(p.desc)
            if (p.sample.isNotBlank()) append("\n\n开场示例：「${p.sample}」")
        }
    }

    private fun refreshPersonaSelection() {
        personaRow.removeAllViews()
        buildPersonaRow()
    }

    private fun markSelected(v: TextView, on: Boolean) {
        v.setBackgroundResource(if (on) R.drawable.bg_tile_on else R.drawable.bg_tile_off)
        // 浅紫底配深字才看得清（v3.2 是白字浅底，实机看不清）
        v.setTextColor(getColor(if (on) R.color.text_main else R.color.text_sub))
    }

    private fun showCustomPersonaDialog() {
        val name = EditText(this)
        name.hint = getString(R.string.persona_name_hint)
        name.inputType = android.text.InputType.TYPE_CLASS_TEXT
        val desc = EditText(this)
        desc.hint = getString(R.string.persona_desc_hint)
        desc.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        desc.minLines = 3
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(8), dp(16), 0)
        box.addView(name)
        box.addView(desc)
        AlertDialog.Builder(this)
            .setTitle(R.string.add_custom_persona)
            .setView(box)
            .setPositiveButton(R.string.done) { _, _ ->
                val n = name.text.toString().trim()
                val d = desc.text.toString().trim()
                if (n.isNotEmpty() && d.isNotEmpty()) {
                    PersonaStore.addCustom(this, n, d)
                    personas = PersonaStore.all(this)
                    personaIdx = 0 // 自定义排在最前
                    refreshPersonaSelection()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun buildPlatformRow() {
        platformRow.removeAllViews()
        Presets.PLATFORMS.forEachIndexed { i, name ->
            val b = chipButton(name)
            b.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            markChip(b, i == platformIdx)
            b.setOnClickListener {
                platformIdx = i
                buildPlatformRow()
            }
            platformRow.addView(b)
        }
    }

    private fun buildWordsRow() {
        wordsRow.removeAllViews()
        Presets.WORDS.forEachIndexed { i, (label, _) ->
            val b = chipButton(label)
            b.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            markChip(b, i == wordsIdx)
            b.setOnClickListener {
                wordsIdx = i
                buildWordsRow()
            }
            wordsRow.addView(b)
        }
    }

    private fun buildRulesRow() {
        rulesRow.removeAllViews()
        Presets.RULES.forEachIndexed { i, (short, full) ->
            val card = LinearLayout(this)
            card.orientation = LinearLayout.HORIZONTAL
            card.gravity = Gravity.CENTER_VERTICAL
            card.setBackgroundResource(R.drawable.bg_card)
            card.elevation = dp(1).toFloat()
            card.setPadding(dp(14), dp(12), dp(14), dp(12))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }

            val info = LinearLayout(this)
            info.orientation = LinearLayout.VERTICAL
            info.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val r1 = TextView(this)
            r1.text = short
            r1.setTextColor(getColor(R.color.text_main))
            r1.textSize = 13.5f
            r1.setTypeface(null, Typeface.BOLD)
            val r2 = TextView(this)
            r2.text = full
            r2.setTextColor(getColor(R.color.text_sub))
            r2.textSize = 11f
            info.addView(r1)
            info.addView(r2)

            val pill = PillToggle(this, "关", "开", ruleStates[i]) { checked -> ruleStates[i] = checked }

            card.addView(info)
            card.addView(pill.view)
            rulesRow.addView(card, lp)
        }
    }

    private fun chipButton(text: String): TextView {
        val b = TextView(this)
        b.text = text
        b.textSize = 13f
        b.setPadding(dp(16), dp(9), dp(16), dp(9))
        return b
    }

    private fun markChip(b: TextView, on: Boolean) {
        b.setBackgroundResource(if (on) R.drawable.bg_pill_on else R.drawable.bg_pill_off)
        b.setTextColor(if (on) 0xFFFFFFFF.toInt() else getColor(R.color.text_sub))
        b.gravity = Gravity.CENTER
    }

    private fun rulesTriples(): List<Triple<String, String, Boolean>> =
        Presets.RULES.mapIndexed { i, (short, full) -> Triple(short, full, ruleStates[i]) }

    /* ---------- 选题 ---------- */

    private fun genTopics() {
        val subjectText = subject.text.toString().trim()
        if (subjectText.isEmpty()) {
            Toast.makeText(this, R.string.err_empty_subject, Toast.LENGTH_SHORT).show()
            return
        }
        if (busy) return
        busy = true
        btnTopics.isEnabled = false
        topicStatus.visibility = View.VISIBLE
        topicStatus.text = getString(R.string.topic_generating)

        val persona = personas[personaIdx.coerceIn(0, personas.size - 1)]
        val platform = Presets.PLATFORMS[platformIdx]
        val words = Presets.WORDS[wordsIdx].second
        val rules = rulesTriples()
        val extra = extraRule.text.toString().trim()
        val subj = subjectText

        Thread {
            try {
                val raw = Llm.ask(
                    this,
                    Prompts.topicsSystem(persona, rules, extra),
                    Prompts.topicsUser(subj, platform, words, hotspot),
                    jsonMode = true
                )
                val dirs = Llm.parseDirections(Llm.extractJson(raw))
                if (dirs.isEmpty()) throw RuntimeException("模型没给出完整方向，请重试")
                runOnUiThread {
                    busy = false
                    btnTopics.isEnabled = true
                    topicStatus.visibility = View.GONE
                    directions = dirs
                    renderDirections()
                    showStep(2)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    btnTopics.isEnabled = true
                    topicStatus.visibility = View.VISIBLE
                    topicStatus.text = "生成失败：${e.message}"
                }
            }
        }.start()
    }

    private fun renderDirections() {
        directionList.removeAllViews()
        directions.forEach { d ->
            val v = layoutInflater.inflate(R.layout.item_direction, directionList, false)
            v.findViewById<TextView>(R.id.dirLabel).text = d.label.ifBlank { "方向" }
            v.findViewById<TextView>(R.id.dirTitle).text = d.title
            v.findViewById<TextView>(R.id.dirAngle).text = d.angle
            v.findViewById<TextView>(R.id.dirHook).text = "钩子：${d.hook}"
            v.findViewById<TextView>(R.id.dirOutline).text = d.outline.joinToString("\n") { "· $it" }
            v.findViewById<TextView>(R.id.btnPick).setOnClickListener { startDraft(d) }
            directionList.addView(v)
        }
    }

    /* ---------- 成稿 ---------- */

    private fun startDraft(d: Direction) {
        if (busy) {
            Toast.makeText(this, "正在生成中，稍等片刻再选", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        content = ""
        savedId = -1L
        showStep(3)
        dock.visibility = View.GONE
        draftTitle.text = d.title
        draftContent.text = ""
        genStatus.visibility = View.VISIBLE
        genStatus.text = getString(R.string.draft_generating)

        val persona = personas[personaIdx.coerceIn(0, personas.size - 1)]
        val platform = Presets.PLATFORMS[platformIdx]
        val words = Presets.WORDS[wordsIdx].second
        val rules = rulesTriples()
        val extra = extraRule.text.toString().trim()
        val subj = subject.text.toString().trim()

        Thread {
            try {
                Llm.stream(
                    this,
                    Prompts.draftSystem(persona, rules, extra, platform, words),
                    Prompts.draftUser(subj, d, hotspot)
                ) { delta ->
                    runOnUiThread {
                        content += delta
                        draftContent.text = content
                        step3Scroll.post { step3Scroll.fullScroll(View.FOCUS_DOWN) }
                    }
                }
                runOnUiThread {
                    busy = false
                    genStatus.visibility = View.GONE
                    dock.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    genStatus.text = "生成失败：${e.message}"
                    genStatus.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    /* ---------- 步骤切换 / 保存 / 分享 ---------- */

    private fun showStep(n: Int) {
        step = n
        step1.visibility = if (n == 1) View.VISIBLE else View.GONE
        step2.visibility = if (n == 2) View.VISIBLE else View.GONE
        step3Scroll.visibility = if (n == 3) View.VISIBLE else View.GONE
        stepTitle.text = when (n) {
            1 -> getString(R.string.step1_title)
            2 -> getString(R.string.step2_title)
            else -> getString(R.string.step3_title)
        }
        btnBack.visibility = if (n == 1) View.INVISIBLE else View.VISIBLE
        btnBack.text = if (n == 2) getString(R.string.btn_gen_topic_back) else "← 换个方向"

        // 步骤条：走过的圆点实心，当前步标签高亮
        sn1.setBackgroundResource(if (n >= 1) R.drawable.bg_pill_on else R.drawable.bg_pill_off)
        sn2.setBackgroundResource(if (n >= 2) R.drawable.bg_pill_on else R.drawable.bg_pill_off)
        sn3.setBackgroundResource(if (n >= 3) R.drawable.bg_pill_on else R.drawable.bg_pill_off)
        lbl1.setTextColor(getColor(if (n >= 1) R.color.text_main else R.color.text_sub))
        lbl2.setTextColor(getColor(if (n >= 2) R.color.text_main else R.color.text_sub))
        lbl3.setTextColor(getColor(if (n >= 3) R.color.text_main else R.color.text_sub))
        ln12.setBackgroundResource(if (n >= 2) R.drawable.bg_pill_on else R.drawable.bg_pill_off)
        ln23.setBackgroundResource(if (n >= 3) R.drawable.bg_pill_on else R.drawable.bg_pill_off)

        // 底部操作区随步骤切换
        btnTopics.visibility = if (n == 1) View.VISIBLE else View.GONE
        topicStatus.visibility = if (n == 1 && busy) View.VISIBLE else View.GONE
        btnRegenBar.visibility = View.GONE
        dock.visibility = if (n == 3 && !busy && content.isNotBlank()) View.VISIBLE else View.GONE
    }

    private fun save() {
        if (ensureSaved() == -1L) {
            Toast.makeText(this, "还没有内容", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, R.string.save_ok, Toast.LENGTH_SHORT).show()
    }

    /** 确保当前成稿已入库；空内容返回 -1 */
    private fun ensureSaved(): Long {
        if (content.isBlank()) return -1L
        if (savedId == -1L) savedId = System.currentTimeMillis()
        val persona = personas[personaIdx.coerceIn(0, personas.size - 1)]
        DraftStore.upsert(
            this,
            DraftItem(
                savedId,
                draftTitle.text.toString().ifBlank { subject.text.toString().trim() },
                persona.name,
                Presets.PLATFORMS[platformIdx],
                subject.text.toString().trim(),
                content,
                System.currentTimeMillis()
            )
        )
        return savedId
    }

    /** 成稿一键全屏提词：自动入库后直达 */
    private fun quickPrompter() {
        val id = ensureSaved()
        if (id == -1L) {
            Toast.makeText(this, "还没有内容", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, PrompterActivity::class.java).putExtra("id", id))
    }

    /** 成稿一键悬浮提词：自动入库后起前台服务；无权限弹两步引导 */
    private fun quickFloat() {
        val id = ensureSaved()
        if (id == -1L) {
            Toast.makeText(this, "还没有内容", Toast.LENGTH_SHORT).show()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.perm_title)
                .setMessage(R.string.perm_steps)
                .setPositiveButton(R.string.perm_go_overlay) { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                }
                .setNeutralButton(R.string.perm_go_details) { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    )
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, OverlayService::class.java).putExtra("id", id)
        )
    }

    private fun share() {
        if (content.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, draftTitle.text.toString())
            putExtra(Intent.EXTRA_TEXT, "${draftTitle.text}\n\n$content")
        }
        startActivity(Intent.createChooser(intent, getString(R.string.btn_share)))
    }

    companion object {
        private const val REQ_NOTIF = 2
    }}
