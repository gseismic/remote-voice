package com.remotevoice.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 装饰性波形（V3.2 原型 wave）：10 根柱子按平滑伪随机曲线起伏。
 * 振幅数据源（真实电平）尚未接入（index.html 开放问题"波形的实际数据源"未定），当前为装饰动画。
 */
class WaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var active = false
    private val barCount = 10
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_ON }
    private val offPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_OFF }
    private val anim = object : Runnable {
        override fun run() {
            if (active && visibility == VISIBLE && windowVisibility == VISIBLE) {
                invalidate()
                postDelayed(this, 40)
            }
        }
    }

    /** 进入/离开传输态；离开后画静默短柱。 */
    fun setActive(on: Boolean) {
        if (active == on) return
        active = on
        if (on) post(anim) else {
            removeCallbacks(anim)
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = resources.displayMetrics.density
        val barW = 4 * d
        val gap = 7 * d
        val total = barCount * barW + (barCount - 1) * gap
        var x = (width - total) / 2f
        val t = System.currentTimeMillis() / 1000f
        for (i in 0 until barCount) {
            val f = if (active) {
                0.15f + 0.8f * abs(sin(t * 3.1f + i * 0.9f) * cos(t * 1.7f + i * 1.3f))
            } else {
                0.06f
            }
            val barH = maxOf(4 * d, height * f)
            canvas.drawRoundRect(
                x, height - barH, x + barW, height.toFloat(), 2 * d, 2 * d,
                if (active) paint else offPaint,
            )
            x += barW + gap
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(anim)
        super.onDetachedFromWindow()
    }

    private companion object {
        val COLOR_ON = 0xFF3FB950.toInt()
        val COLOR_OFF = 0xFF27303D.toInt()
    }
}
