package com.mxd.koubo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 稿件详情：编辑文案 + 一键全屏提词 / 悬浮提词 */
class DraftActivity : AppCompatActivity() {

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_draft)

        val id = intent.getLongExtra("id", -1L)
        val d = DraftStore.byId(this, id)
        if (d == null) {
            finish()
            return
        }

        val etTitle = findViewById<EditText>(R.id.title)
        val etContent = findViewById<EditText>(R.id.content)
        val meta = findViewById<TextView>(R.id.meta)

        etTitle.setText(d.title)
        etContent.setText(d.content)
        meta.text = "${d.personaName} · ${d.platform} · ${d.content.length}字 · ${fmt.format(Date(d.updatedAt))}"

        // ── 提词入口：大按钮 = 悬浮提词挂相机；次级 = App 内全屏 ──
        findViewById<TextView>(R.id.btnPrompter).setOnClickListener { launchOverlay(d.id) }
        findViewById<TextView>(R.id.btnFloat).setOnClickListener {
            startActivity(Intent(this, PrompterActivity::class.java).putExtra("id", d.id))
        }

        // ── 编辑 / 分享 / 删除 ──
        findViewById<TextView>(R.id.btnSave).setOnClickListener {
            val list = DraftStore.load(this)
            val cur = list.firstOrNull { it.id == d.id } ?: return@setOnClickListener
            cur.title = etTitle.text.toString().trim().ifBlank { cur.title }
            cur.content = etContent.text.toString()
            cur.updatedAt = System.currentTimeMillis()
            DraftStore.save(this, list)
            meta.text = "${cur.personaName} · ${cur.platform} · ${cur.content.length}字 · ${fmt.format(Date(cur.updatedAt))}"
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
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
