package com.domanovsky.roadlimit

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setBackgroundColor(0xFF101820.toInt())
        }

        val title = TextView(this).apply {
            text = "ОГРАНИЧЕНИЕ СКОРОСТИ"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }

        val value = TextView(this).apply {
            text = "—"
            textSize = 88f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }

        val unit = TextView(this).apply {
            text = "км/ч"
            textSize = 22f
            setTextColor(0xFFB0BEC5.toInt())
            gravity = Gravity.CENTER
        }

        val status = TextView(this).apply {
            text = "Ожидание подключения GPS"
            textSize = 16f
            setTextColor(0xFFB0BEC5.toInt())
            gravity = Gravity.CENTER
        }

        content.addView(title)
        content.addView(value)
        content.addView(unit)
        content.addView(status)
        setContentView(content)
    }
}
