package com.netquality.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/** نمودار تاخیر در طول زمان (مثل PingPlotter). مقدار منفی = Packet Loss */
class GraphView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var data: List<Double> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val maxPoints = 150

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1976D2.toInt(); strokeWidth = 3f; style = Paint.Style.STROKE
    }
    private val lossPaint = Paint().apply { color = 0xFFD32F2F.toInt(); strokeWidth = 4f }
    private val gridPaint = Paint().apply { color = 0xFFDDDDDD.toInt(); strokeWidth = 1f }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF555555.toInt(); textSize = 26f
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        val padL = 80f
        val padT = 16f
        val padB = 16f
        val plotW = w - padL - 6f
        val plotH = h - padT - padB

        val d = data.takeLast(maxPoints)
        val top = max(10.0, (d.maxOrNull() ?: 10.0)) * 1.15

        for (i in 0..3) {
            val y = padT + plotH * i / 3f
            c.drawLine(padL, y, w, y, gridPaint)
            val v = top * (3 - i) / 3
            c.drawText(v.toInt().toString(), 4f, y + 9f, textPaint)
        }
        if (d.isEmpty()) return

        val step = plotW / (maxPoints - 1)
        val offset = maxPoints - d.size
        var px = -1f
        var py = -1f
        d.forEachIndexed { i, v ->
            val x = padL + step * (i + offset)
            if (v < 0) {
                c.drawLine(x, padT, x, padT + plotH, lossPaint)
                px = -1f
            } else {
                val y = padT + plotH - (v / top).toFloat() * plotH
                if (px >= 0) c.drawLine(px, py, x, y, linePaint)
                px = x
                py = y
            }
        }
    }
}
