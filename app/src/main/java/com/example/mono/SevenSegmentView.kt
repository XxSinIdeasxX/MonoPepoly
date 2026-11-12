package com.example.mono

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

// LCD-style amount: right-aligned digits drawn as 7 segments each, unused segments stay faintly lit
class SevenSegmentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var value = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val seg = RectF()

    private val onColor = Color.parseColor("#FFB300")
    private val offColor = Color.parseColor("#2B2415")

    fun setValue(amount: Int) {
        value = amount.coerceIn(0, MAX_VALUE)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        // Cell width limited by both the available width and a pleasant digit aspect ratio
        val w = minOf(width / (DIGITS + (DIGITS - 1) * GAP), h * 0.55f)
        val total = w * DIGITS + w * GAP * (DIGITS - 1)
        val text = value.toString()
        canvas.save()
        canvas.translate(width - total, 0f)
        canvas.skew(-0.08f, 0f)
        for (i in 0 until DIGITS) {
            val ch = text.getOrNull(i - (DIGITS - text.length))
            drawDigit(canvas, i * w * (1 + GAP), w, h, if (ch == null) 0 else MASKS[ch - '0'])
        }
        canvas.restore()
    }

    private fun drawDigit(canvas: Canvas, x: Float, w: Float, h: Float, mask: Int) {
        val t = w * 0.17f
        val g = t * 0.15f
        val mid = h / 2
        // segments a..g
        val rects = arrayOf(
            RectF(t + g, 0f, w - t - g, t),
            RectF(w - t, t + g, w, mid - g),
            RectF(w - t, mid + g, w, h - t - g),
            RectF(t + g, h - t, w - t - g, h),
            RectF(0f, mid + g, t, h - t - g),
            RectF(0f, t + g, t, mid - g),
            RectF(t + g, mid - t / 2, w - t - g, mid + t / 2)
        )
        rects.forEachIndexed { i, r ->
            paint.color = if (mask and (1 shl i) != 0) onColor else offColor
            seg.set(r.left + x, r.top, r.right + x, r.bottom)
            canvas.drawRoundRect(seg, t / 2, t / 2, paint)
        }
    }

    companion object {
        const val DIGITS = 6
        const val MAX_VALUE = 999_999
        private const val GAP = 0.3f

        // bit i = segment a..g
        private val MASKS = intArrayOf(0b0111111, 0b0000110, 0b1011011, 0b1001111, 0b1100110, 0b1101101, 0b1111101, 0b0000111, 0b1111111, 0b1101111)
    }
}
