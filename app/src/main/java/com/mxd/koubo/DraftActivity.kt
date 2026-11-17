package com.mxd.koubo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 稿件创作工作台：编辑文案 + 钩子工坊 + 标题封面工坊 + 发布台账 + 一键提词。
 * 三张卡对应播放量杠杆 #2（封面标题）/#3（前3-5秒钩子）/#7（发布数据回流）。
 */
class DraftActivity : AppCompatActivity() {

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
    private val pubFmt = SimpleDateFormat("MM-dd", Locale.CHINA)
    private var draftId = -1L

    private lateinit var etTitle: EditText
    private lateinit var etContent: EditText
    private lateinit var meta: TextView
    private lateinit var hookBadge: TextView
    private lateinit var tvHook: TextView
    private lateinit var coverBadge: TextView
    private lateinit var tvCover: TextView
    private lateinit var llPublishes: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_draft)

        draftId = intent.getLongExtra("id", -1L)
        val d = DraftStore.byId(this, draftId)
        if (d == null) {
            finish()
            return
        }

        etTitle = findViewById(R.id.title)
        etContent = findViewById(R.id.content)
        meta = findViewById(R.id.meta)
        hookBadge = findViewById(R.id.hookBadge)
        tvHook = findViewById(R.id.tvHook)
        coverBadge = findViewById(R.id.coverBadge)
        tvCover = findViewById(R.id.tvCover)
        llPublishes = findViewById(R.id.llPublishes)

        etTitle.setText(d.title)
        etContent.setText(d.content)

        // ── 提词入口：大按钮 = 悬浮提词挂相机；次级 = App 内全屏 ──
        findViewById<TextView>(R.id.btnPrompter).setOnClickListener { launchOverlay(d.id) }
        findViewById<TextView>(R.id.btnFloat).setOnClickListener {
            startActivity(Intent(this, PrompterActivity::class.java).putExtra("id", d.id))
        }

        // ── 播放量杠杆工坊 ──
        findViewById<TextView>(R.id.btnHookWork).setOnClickListener {
            Workshops.showHook(this, draftId) {
                // 关键：把 store 里的新开头同步回编辑器，否则「保存」会用旧内容回滚钩子
                val cur = DraftStore.byId(this, draftId)
                if (cur != null) etContent.setText(cur.content)
                refreshWorkbench()
            }
        }
        findViewById<TextView>(R.id.btnTitleWork).setOnClickListener {
            Workshops.showTitle(this, draftId) {
                val cur = DraftStore.byId(this, draftId)
                if (cur != null) etTitle.setText(cur.title)
                refreshWorkbench()
            }
        }
        findViewById<TextView>(R.id.btnPublishAdd).setOnClickListener {
            Workshops.showPublish(this, draftId) { refreshPublishes() }
        }

        // ── 编辑 / 分享 / 删除 ──
        findViewById<TextView>(R.id.btnSave).setOnClickListener {
            val list = DraftStore.load(this)
            val cur = list.firstOrNull { it.id == d.id } ?: return@setOnClickListener
            cur.title = etTitle.text.toString().trim().ifBlank { cur.title }
            cur.content = etContent.text.toString()
            cur.updatedAt = System.currentTimeMillis()
            DraftStore.save(this, list)
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            refreshWorkbench()
        }

        findViewById<TextView>(R.id.btnShare).setOnClickListener {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, etTitle.text.toString())
                putExtra(Intent.EXTRA_TEXT, "${etTitle.text}\n\n${etContent.text}")
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
        }

        findViewById<TextView>(R.id.btnDel).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.confirm_delete)
                .setPositiveButton(R.string.del) { _, _ ->
                    DraftStore.delete(this, d.id)
                    finish()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        refreshWorkbench()
    }

    /** 从存储重读，刷新三张工作台卡片 */
    private fun refreshWorkbench() {
        val d = DraftStore.byId(this, draftId) ?: return

        meta.text = "${d.personaName} · ${d.platform} · ${d.content.length}字 · ${fmt.format(Date(d.updatedAt))}"

        // 钩子卡
        if (d.hookScore >= 0) {
            hookBadge.visibility = android.view.View.VISIBLE
            hookBadge.setBackgroundResource(Workshops.scoreBg(d.hookScore))
            hookBadge.text = "钩子 ${d.hookScore}"
        } else {
            hookBadge.visibility = android.view.View.GONE
        }
        tvHook.text = when {
            d.hookText.isNotBlank() -> "当前：${d.hookText}"
            d.content.isNotBlank() -> "当前开头：${d.content.take(48)}"
            else -> "还没有内容，先写稿或用 AI 生成"
        }

        // 标题封面卡
        coverBadge.visibility = if (d.coverText.isNotBlank()) android.view.View.VISIBLE else android.view.View.GONE
        tvCover.text = if (d.coverText.isNotBlank()) {
            val sub = if (d.coverSub.isNotBlank()) " · ${d.coverSub}" else ""
            "封面大字：${d.coverText}$sub"
        } else {
            "还没设计封面：标题 + 封面大字成对生成，点击率的第一道门"
        }

        refreshPublishes()
    }

    private fun refreshPublishes() {
        val d = DraftStore.byId(this, draftId) ?: return
        Workshops.renderPublishRows(this, llPublishes, d.publishes, pubFmt) { rec ->
            val list = DraftStore.load(this)
            val cur = list.firstOrNull { it.id == draftId } ?: return@renderPublishRows
            cur.publishes.removeAll { it.id == rec.id }
            DraftStore.save(this, list)
            refreshPublishes()
        }
    }

    /** 悬浮提词：无权限时弹两步引导（ColorOS 未知来源应用默认拦截，需先解除限制） */
    private fun launchOverlay(id: Long) {
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

    companion object {
        private const val REQ_NOTIF = 2
    }
}
