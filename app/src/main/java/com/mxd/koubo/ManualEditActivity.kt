package com.mxd.koubo

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** 手动写稿：不经过 AI，直接建一篇稿件进统一稿件库 */
class ManualEditActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)
        setTitle(R.string.manual_title)

        val etTitle = findViewById<EditText>(R.id.title)
        val etContent = findViewById<EditText>(R.id.content)
        val save = findViewById<TextView>(R.id.save)

        save.setOnClickListener {
            val t = etTitle.text.toString().trim()
            val c = etContent.text.toString()
            if (t.isEmpty() && c.isBlank()) {
                Toast.makeText(this, "标题和内容至少填一个", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val now = System.currentTimeMillis()
            DraftStore.upsert(
                this,
                DraftItem(now, t.ifEmpty { c.take(12) }, "手动", "通用", c, c, now)
            )
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
